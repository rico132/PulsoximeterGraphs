#include "BleGattServer.h"

#include <cstring>
#include <vector>

#include <esp_random.h>

#include "Config.h"

namespace {
// Generates a fresh random passkey and persists it — shared by first-ever-boot provisioning
// (loadOrGenerateBlePasskey() below), the explicit `blepin` serial "regenerate" signal (see
// BleGattServer::regeneratePairingPasskey()), and UNPAIR_ALL_DEVICES (see handleControlWrite's
// own case for why that one also rotates the PIN, not just the bond list). Never a fixed literal
// baked into source: see Config::kPrefsKeyBlePasskey's own comment for why a hardcoded PIN
// wouldn't actually be a secret. esp_random() is a real HWRNG on the ESP32, safe to use directly
// with no seeding needed.
uint32_t generateAndStoreNewPasskey(Preferences &preferences) {
  const uint32_t passkey = esp_random() % 1000000UL; // 000000-999999
  preferences.putULong(Config::kPrefsKeyBlePasskey, passkey);
  return passkey;
}

void printPasskey(uint32_t passkey) {
  Serial.println("========================================================");
  Serial.printf("BleGattServer: BLE pairing PIN: %06u\n", passkey);
  Serial.println(
      "Enter this PIN when your phone asks to pair with 'PulsoxRelay'. Send "
      "a bare 'blepin' over serial any time to generate a new one.");
  Serial.println("========================================================");
}

// Loads the persisted BLE pairing PIN, generating a fresh one only on first boot ever (or after
// an NVS wipe) — see generateAndStoreNewPasskey()'s own comment. Printed to Serial every boot
// (not just when freshly generated) so it's always retrievable by just power-cycling and
// watching the log, with no separate "did I ever write this down" bookkeeping required.
uint32_t loadOrGenerateBlePasskey(Preferences &preferences) {
  uint32_t passkey;
  if (preferences.isKey(Config::kPrefsKeyBlePasskey)) {
    passkey = static_cast<uint32_t>(
        preferences.getULong(Config::kPrefsKeyBlePasskey, 0));
  } else {
    passkey = generateAndStoreNewPasskey(preferences);
    Serial.println(
        "BleGattServer: no BLE pairing PIN was set yet — generated a new "
        "one (see below).");
  }
  printPasskey(passkey);
  return passkey;
}
} // namespace

BleGattServer::BleGattServer(ICsvBuffer &csvBuffer, ClockSync &clockSync,
                             StoredRecordDownloader &storedRecordDownloader,
                             UsbHidOxHost &usbHost)
    : csvBuffer_(csvBuffer), clockSync_(clockSync),
      storedRecordDownloader_(storedRecordDownloader), usbHost_(usbHost) {}

void BleGattServer::ServerCallbacks::onConnect(NimBLEServer * /*server*/,
                                               NimBLEConnInfo &connInfo) {
  owner_.connHandle_ = connInfo.getConnHandle();
  owner_.connected_ = true;
  Serial.printf("BleGattServer: phone connected (handle=%u).\n",
               connInfo.getConnHandle());
}

void BleGattServer::ServerCallbacks::onDisconnect(NimBLEServer * /*server*/,
                                                  NimBLEConnInfo & /*connInfo*/,
                                                  int reason) {
  // Per the plan: if the phone disconnects mid REQUEST_DATA dump, just stop
  // — since only CLEAR_BUFFER discards data, the next REQUEST_DATA naturally
  // resumes from the same unclaimed buffer, so no resend/ack bookkeeping is
  // needed here.
  owner_.connected_ = false;
  // A firmware update abandoned mid-transfer (phone crashed, walked out of range, ...) would
  // otherwise leave BleFirmwareUpdater::inProgress() stuck true forever — every future
  // START_FIRMWARE_UPDATE would then be refused as "already in progress" until a manual power
  // cycle. Aborting here doesn't touch the boot partition (see BleFirmwareUpdater's own header
  // doc for why that's always safe), so this is purely "free up the ability to try again" —
  // nothing an already-in-flight update needs to survive a disconnect for.
  owner_.firmwareUpdater_.abort();
  Serial.printf(
      "BleGattServer: phone disconnected (reason=%d) — advertising again.\n",
      reason);
  NimBLEDevice::startAdvertising();
}

void BleGattServer::ServerCallbacks::onMTUChange(uint16_t /*mtu*/,
                                                 NimBLEConnInfo & /*connInfo*/) {
  // No action needed: requestDataDump() reads the negotiated MTU fresh from
  // the server at send time rather than caching it here.
}

void BleGattServer::ControlCallbacks::onWrite(
    NimBLECharacteristic *characteristic, NimBLEConnInfo & /*connInfo*/) {
  const NimBLEAttValue value = characteristic->getValue();
  owner_.handleControlWrite(value.data(), value.size());
}

void BleGattServer::FirmwareCallbacks::onWrite(
    NimBLECharacteristic *characteristic, NimBLEConnInfo & /*connInfo*/) {
  const NimBLEAttValue value = characteristic->getValue();
  owner_.handleFirmwareChunk(value.data(), value.size());
}

void BleGattServer::StatusCallbacks::onRead(NimBLECharacteristic *characteristic,
                                            NimBLEConnInfo & /*connInfo*/) {
  // The only thing worth reading off Status today (see Config::kStatusFirmwareVersion's own
  // doc) — a plain read, unlike the update-result notification below, so the app's "check for
  // update" flow can learn the running version without first triggering anything else.
  const size_t versionLen = strlen(Config::kFirmwareVersion);
  std::vector<uint8_t> value(1 + versionLen);
  value[0] = Config::kStatusFirmwareVersion;
  std::memcpy(value.data() + 1, Config::kFirmwareVersion, versionLen);
  characteristic->setValue(value.data(), value.size());
}

void BleGattServer::begin() {
  preferences_.begin(Config::kPrefsNamespace, /*readOnly=*/false);

  NimBLEDevice::init(Config::kDeviceName);
  NimBLEDevice::setMTU(Config::kPreferredMtu);

  // Require pairing — bonded, encrypted, and authenticated (MITM-protected
  // via the passkey below) — before the stack will accept a write or read
  // against any *_ENC/*_AUTHEN characteristic (see below). Without this,
  // literally anyone within BLE range could connect to a "PulsoxRelay" and
  // read someone's SpO2/pulse history with zero access control — see
  // Config::kPrefsKeyBlePasskey's own comment for the full reasoning.
  // sc=true (LE Secure Connections, ECDH-based key exchange) rather than
  // legacy pairing — supported by every Android version this app targets.
  NimBLEDevice::setSecurityAuth(/*bonding=*/true, /*mitm=*/true, /*sc=*/true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityPasskey(loadOrGenerateBlePasskey(preferences_));

  server_ = NimBLEDevice::createServer();
  server_->setCallbacks(&serverCallbacks_);

  NimBLEService *service = server_->createService(Config::kServiceUuid);

  // WRITE alone declares the operation type; _ENC/_AUTHEN layer the actual
  // access-control requirement on top — the stack rejects a write from an
  // unpaired/unauthenticated link with an ATT "Insufficient Authentication"
  // error rather than ever reaching handleControlWrite() at all. Every
  // control opcode (REQUEST_DATA, SET_TIME, CLEAR_BUFFER, ...) goes through
  // this one characteristic, so gating it here is what actually protects all
  // of them — nothing downstream (the Data dump, USB re-download, ...) can
  // ever be triggered without first getting past this.
  controlChar_ = service->createCharacteristic(
      Config::kControlCharUuid, NIMBLE_PROPERTY::WRITE |
                                    NIMBLE_PROPERTY::WRITE_ENC |
                                    NIMBLE_PROPERTY::WRITE_AUTHEN);
  controlChar_->setCallbacks(&controlCallbacks_);

  // READ_ENC/READ_AUTHEN here mainly guards the CCCD (notification
  // subscribe) — defense in depth alongside the Control gate above, which is
  // what actually prevents an unauthenticated connection from ever making
  // the ESP32 send anything on this characteristic in the first place (see
  // its own comment).
  dataChar_ = service->createCharacteristic(
      Config::kDataCharUuid, NIMBLE_PROPERTY::NOTIFY |
                                 NIMBLE_PROPERTY::READ_ENC |
                                 NIMBLE_PROPERTY::READ_AUTHEN);

  // Stretch / not-MVP per PROTOCOL.md, but declared for scan-filter/GATT
  // completeness: reports the current buffered row count on read. Gated the
  // same way as Data above, for the same reason — it's otherwise plain
  // NIMBLE_PROPERTY::READ, which would let an unauthenticated connection
  // read it directly.
  statusChar_ = service->createCharacteristic(
      Config::kStatusCharUuid, NIMBLE_PROPERTY::READ |
                                   NIMBLE_PROPERTY::READ_ENC |
                                   NIMBLE_PROPERTY::READ_AUTHEN |
                                   NIMBLE_PROPERTY::NOTIFY);
  statusChar_->setCallbacks(&statusCallbacks_);

  // WRITE_NR (write *without* response), unlike Control — see PROTOCOL.md's "BLE firmware
  // update" section for the full throughput reasoning. The old design used a plain WRITE here so
  // each chunk's ATT-level ack paced the phone into sending the next one; that meant a full
  // write-request/write-response round trip per ~500-byte chunk, capping a multi-hundred-KB
  // image to roughly one chunk per BLE connection interval. WRITE_NR lets the phone queue many
  // chunks back-to-back with no per-chunk round trip, which is safe here specifically because:
  // (1) BLE's link layer is itself a reliable, in-order transport — "no response" only skips the
  // *application-level* ack, not delivery guarantees, so nothing can arrive corrupted or out of
  // order; (2) this NimBLE host processes queued ATT operations for a connection strictly in the
  // order received, and handleFirmwareChunk() below calls Update::write() synchronously before
  // returning, so the *next* opcode after the last chunk (FINISH_FIRMWARE_UPDATE, sent as a
  // Control write) can only be handled once every prior chunk has actually finished being
  // written to flash — ordering and completion are still both guaranteed, just without the
  // round-trip cost; (3) ESP32 SPI flash writes easily outrun realistic BLE throughput even at
  // 2M PHY, so this NimBLE host task backing up behind slow flash writes isn't a real risk. Gated
  // by the same *_ENC/*_AUTHEN pairing requirement as every other characteristic here — an
  // arbitrary phone flashing arbitrary firmware onto this device is now possible at all, so this
  // absolutely must never be reachable from an unauthenticated link (see
  // Config::kPrefsKeyBlePasskey's own comment for the full reasoning, which applies here even
  // more than to reading health data). Note that a WRITE_NR (ATT "Write Command") that fails
  // this security check is silently dropped rather than erroring back to the phone — the BLE
  // spec gives commands no error-response mechanism at all — but by the time any chunk is ever
  // sent the link has already had to pass this exact same gate on the preceding
  // START_FIRMWARE_UPDATE control write, which does get an explicit ack, so this is never
  // actually reachable in practice.
  firmwareChar_ = service->createCharacteristic(
      Config::kFirmwareCharUuid, NIMBLE_PROPERTY::WRITE_NR |
                                     NIMBLE_PROPERTY::WRITE_ENC |
                                     NIMBLE_PROPERTY::WRITE_AUTHEN);
  firmwareChar_->setCallbacks(&firmwareCallbacks_);

  // NimBLE-Arduino 2.x: services are started implicitly when the server
  // starts advertising; NimBLEService::start() is a deprecated no-op kept
  // only for source compatibility with 1.x code.
  server_->start();

  NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
  advertising->setName(Config::kDeviceName);
  advertising->addServiceUUID(Config::kServiceUuid);
  advertising->enableScanResponse(true);
  advertising->start();

  requestDataSignal_ = xSemaphoreCreateBinary();
  xTaskCreate(&BleGattServer::dataSendTaskTrampoline, "bleDataSend",
             8192, this, tskIDLE_PRIORITY + 1, nullptr);

  Serial.printf("BleGattServer: advertising as '%s'.\n", Config::kDeviceName);
}

void BleGattServer::dataSendTaskTrampoline(void *param) {
  static_cast<BleGattServer *>(param)->dataSendTaskLoop();
}

void BleGattServer::dataSendTaskLoop() {
  for (;;) {
    if (xSemaphoreTake(requestDataSignal_, portMAX_DELAY) == pdTRUE) {
      requestDataDump();
    }
  }
}

void BleGattServer::handleControlWrite(const uint8_t *data, size_t length) {
  if (length == 0) {
    return;
  }
  const uint8_t opcode = data[0];
  switch (opcode) {
  case Config::kOpRequestData:
    Serial.println("BleGattServer: REQUEST_DATA received.");
    // Signal the dedicated send task rather than dumping here — this
    // callback runs on the NimBLE host task and must return quickly.
    xSemaphoreGive(requestDataSignal_);
    break;

  case Config::kOpSetTime: {
    if (length < 9) {
      Serial.println("BleGattServer: SET_TIME received but too short — "
                     "ignoring.");
      break;
    }
    int64_t epochSeconds = 0;
    for (int i = 0; i < 8; i++) {
      epochSeconds |= static_cast<int64_t>(data[1 + i]) << (8 * i);
    }
    clockSync_.setTime(epochSeconds);
    Serial.printf("BleGattServer: SET_TIME received, epoch=%lld.\n",
                 static_cast<long long>(epochSeconds));
    break;
  }

  case Config::kOpClearBuffer:
    csvBuffer_.clear();
    // Also forget which records were already "committed" to the buffer —
    // see StoredRecordDownloader::resetCommittedRecords()'s comment: their
    // rows no longer exist to skip re-downloading now that the buffer
    // itself is empty.
    storedRecordDownloader_.resetCommittedRecords();
    // The phone has now confirmed durable receipt of everything that was in
    // the buffer — safe to delete the matching records from the PO-400
    // itself, but only once actually asked to (deleteConfirmedRecords()
    // itself no-ops while test mode is on — Config::kDefaultTestMode's own
    // "never destroy real data" default) and only from usbTask, the one
    // task allowed to talk to the device over USB — see
    // StoredRecordDownloader::onDeleteRequested()'s own comment.
    storedRecordDownloader_.requestDeleteOfConfirmedRecords();
    Serial.println("BleGattServer: CLEAR_BUFFER received — buffer wiped.");
    break;

  case Config::kOpSetTestMode:
    if (length >= 2) {
      storedRecordDownloader_.setTestMode(data[1] != 0);
      Serial.printf("BleGattServer: SET_TEST_MODE received — test mode now "
                   "%s.\n",
                   data[1] != 0 ? "ON" : "OFF");
    }
    break;

  case Config::kOpStartFirmwareUpdate:
    handleStartFirmwareUpdate(data, length);
    break;

  case Config::kOpFinishFirmwareUpdate:
    handleFinishFirmwareUpdate();
    break;

  case Config::kOpAbortFirmwareUpdate:
    Serial.println("BleGattServer: ABORT_FIRMWARE_UPDATE received.");
    firmwareUpdater_.abort();
    break;

  case Config::kOpUnpairAllDevices: {
    const int bondCount = NimBLEDevice::getNumBonds();
    NimBLEDevice::deleteAllBonds();
    // Also rotate the pairing PIN, not just the bond list: clearing bonds alone only forces a
    // *re-pair*, not a lockout — anyone who already knows the current PIN (or a phone that was
    // just unpaired) could simply pair right back with it. A fresh PIN means re-pairing actually
    // requires reading the new one off the serial log, i.e. physical access, matching what
    // "unpair everyone" is presumably meant to achieve.
    const uint32_t newPasskey = generateAndStoreNewPasskey(preferences_);
    NimBLEDevice::setSecurityPasskey(newPasskey);
    Serial.printf(
        "BleGattServer: UNPAIR_ALL_DEVICES received — cleared %d bonded "
        "device(s) and generated a new pairing PIN.\n",
        bondCount);
    printPasskey(newPasskey);
    // This phone's own bond was just deleted along with everyone else's — the link is still
    // nominally connected but no longer backed by any bond, so disconnect it now rather than
    // leave it in that inconsistent state. onDisconnect() already re-starts advertising, so
    // this phone (or any other) can pair fresh once they read the new PIN above.
    if (connected_ && server_) {
      server_->disconnect(connHandle_);
    }
    break;
  }

  default:
    Serial.printf("BleGattServer: unknown control opcode 0x%02X ignored.\n",
                 opcode);
    break;
  }
}

void BleGattServer::requestDataDump() {
  if (!connected_ || !dataChar_ || !server_) {
    return;
  }

  // Re-trigger a fresh USB download only if this device-pairing's buffer has
  // actually been read out over BLE at least once since the last one (natural
  // attach or BLE-triggered alike) — see
  // StoredRecordDownloader::bufferReadSinceLastDownload()'s own comment. A
  // download already happening (e.g. a real attach raced this) or a buffer
  // nothing has claimed yet both mean the current content is already as
  // fresh as it can be, so triggering another one on top would just waste
  // USB airtime re-fetching data nothing has actually consumed. The other
  // two ways new data ever makes it into the buffer — a fresh physical
  // attach, or the ESP32 restarting with the PO-400 already plugged in —
  // are both handled entirely by UsbHidOxHost's own attach detection,
  // independently of REQUEST_DATA or this flag.
  if (!storedRecordDownloader_.downloadInProgress() &&
      storedRecordDownloader_.bufferReadSinceLastDownload()) {
    // Forgets which records were already committed (see
    // resetCommittedRecords()'s own comment; their rows may well have
    // already been cleared out of the buffer by an earlier CLEAR_BUFFER) and
    // fires the same attach callback a real unplug/replug would, without
    // requiring one. The phone dedupes against its own database instead
    // (see ReadingsRepository.importCsv), so re-sending everything the
    // device still has is safe — simpler, and far less fragile, than this
    // firmware trying to track "what's actually new since last time" itself.
    storedRecordDownloader_.resetCommittedRecords();
    usbHost_.triggerAttachCallback();
    // triggerAttachCallback() only signals usbTask (a separate FreeRTOS
    // task) to start — briefly yield so it actually gets to wake up and
    // flip downloadInProgress() to true before the check just below, or
    // this would instead see "nothing in progress yet" and fall straight
    // through to dumping whatever was in the buffer *before* this trigger,
    // missing everything the fresh download is about to add.
    vTaskDelay(pdMS_TO_TICKS(100));
  }

  // If a USB stored-record download is still in progress, wait for it
  // rather than immediately dumping whatever subset of records happens to
  // be buffered so far — otherwise REQUEST_DATA only ever returns however
  // much has been appended by the exact moment it was pressed, requiring
  // one sync per record as each finishes downloading. Runs on this
  // dedicated task (not the NimBLE write callback), so it's safe to block
  // here; bounded generously since a full multi-record session can take a
  // while, but not unboundedly in case something's genuinely stuck.
  if (storedRecordDownloader_.downloadInProgress()) {
    Serial.println(
        "BleGattServer: REQUEST_DATA received while a USB download is in "
        "progress — waiting for it to finish before dumping...");
    notifyUsbDownloadState(/*inProgress=*/true);
    constexpr uint32_t kMaxWaitMs = 5UL * 60UL * 1000UL; // 5 minutes
    const unsigned long waitStartMs = millis();
    unsigned long lastLogMs = waitStartMs;
    while (storedRecordDownloader_.downloadInProgress() && connected_) {
      if (millis() - waitStartMs >= kMaxWaitMs) {
        Serial.println(
            "BleGattServer: still waiting after 5 minutes — proceeding "
            "with whatever is currently buffered instead of waiting "
            "indefinitely.");
        break;
      }
      if (millis() - lastLogMs >= 5000) {
        lastLogMs = millis();
        Serial.println(
            "BleGattServer: ...still waiting for the USB download to "
            "finish...");
        // Re-sent periodically, not just once entering this block — see
        // Config::kStatusUsbDownloadState's own doc for why: the phone's inactivity timeout is
        // re-armed by any Status notification, so this is what keeps a legitimately long
        // multi-record download from being mistaken for a stalled connection.
        notifyUsbDownloadState(/*inProgress=*/true);
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
    if (!connected_) {
      Serial.println(
          "BleGattServer: phone disconnected while waiting for the USB "
          "download to finish — abandoning this dump.");
      return;
    }
    // Explicit, rather than leaving the phone to infer "download over" purely from the first
    // Data notification arriving — see Config::kStatusUsbDownloadState's own doc.
    notifyUsbDownloadState(/*inProgress=*/false);
  }

  uint16_t mtu = server_->getPeerMTU(connHandle_);
  if (mtu == 0) {
    mtu = Config::kMinMtu;
  }
  size_t chunkSize = mtu > 3 ? static_cast<size_t>(mtu - 3) : 20;
  if (chunkSize < 20) {
    chunkSize = 20;
  }

  Serial.printf(
      "BleGattServer: starting data dump (mtu=%u, chunkSize=%u bytes).\n",
      mtu, static_cast<unsigned>(chunkSize));

  size_t totalBytesSent = 0;
  csvBuffer_.forEachChunk(chunkSize, [this,
                                     &totalBytesSent](const uint8_t *data,
                                                      size_t len) {
    if (!connected_) {
      return; // Phone disconnected mid-dump: just stop (see onDisconnect).
    }
    int attempts = 0;
    while (!dataChar_->notify(data, len, connHandle_) && attempts < 20 &&
          connected_) {
      vTaskDelay(pdMS_TO_TICKS(10));
      attempts++;
    }
    totalBytesSent += len;
  });
  // The buffer has now actually been read out, regardless of totalBytesSent (even an empty
  // buffer still means "checked, nothing there") and regardless of whether the phone stays
  // connected long enough to receive all of it — see bufferReadSinceLastDownload()'s own comment
  // for what this unlocks for the *next* REQUEST_DATA.
  storedRecordDownloader_.markBufferRead();

  if (connected_) {
    const uint8_t terminator = Config::kEndOfTransferByte;
    // Repeated, each after its own brief pause — see Config::kTerminatorSendCount's comment for
    // why a single, immediate send of this specific packet has been the one to occasionally go
    // missing on real hardware.
    for (int i = 0; i < Config::kTerminatorSendCount && connected_; i++) {
      vTaskDelay(pdMS_TO_TICKS(Config::kTerminatorSendDelayMs));
      dataChar_->notify(&terminator, 1, connHandle_);
    }
    Serial.printf(
        "BleGattServer: data dump finished, sent %u bytes.\n",
        static_cast<unsigned>(totalBytesSent));
  } else {
    Serial.println(
        "BleGattServer: data dump aborted — phone disconnected mid-dump.");
  }
}

void BleGattServer::handleStartFirmwareUpdate(const uint8_t *data,
                                              size_t length) {
  // [opcode:1][size:4 LE][md5hex:32 ASCII] — see Config::kOpStartFirmwareUpdate's own doc.
  constexpr size_t kExpectedLength = 1 + 4 + 32;
  if (length != kExpectedLength) {
    Serial.printf(
        "BleGattServer: START_FIRMWARE_UPDATE received with wrong length "
        "(%u, expected %u) — ignoring.\n",
        static_cast<unsigned>(length), static_cast<unsigned>(kExpectedLength));
    notifyFirmwareUpdateResult(false, 0xFF);
    return;
  }

  // Refused rather than merely delayed: unlike REQUEST_DATA (which can safely wait for a USB
  // download already in flight, see requestDataDump()'s own comment), letting a phone queue a
  // firmware flash to start automatically the instant USB traffic settles is a much bigger
  // surprise to leave latent — better the phone sees an immediate, explicit failure and retries
  // once it knows the device is actually free.
  if (storedRecordDownloader_.downloadInProgress()) {
    Serial.println(
        "BleGattServer: START_FIRMWARE_UPDATE refused — a USB stored-record "
        "download is in progress.");
    notifyFirmwareUpdateResult(false, 0xFF);
    return;
  }

  uint32_t size = 0;
  for (int i = 0; i < 4; i++) {
    size |= static_cast<uint32_t>(data[1 + i]) << (8 * i);
  }
  char md5Hex[33];
  std::memcpy(md5Hex, data + 5, 32);
  md5Hex[32] = '\0';

  BleFirmwareUpdater::BeginResult beginResult;
  if (!firmwareUpdater_.begin(size, md5Hex, &beginResult)) {
    Serial.printf(
        "BleGattServer: START_FIRMWARE_UPDATE failed (BeginResult=%d).\n",
        static_cast<int>(beginResult));
    notifyFirmwareUpdateResult(false, 0xFF);
    return;
  }

  firmwareExpectedSize_ = size;
  firmwareBytesReceived_ = 0;
  Serial.printf(
      "BleGattServer: START_FIRMWARE_UPDATE accepted, expecting %u bytes.\n",
      static_cast<unsigned>(size));
}

void BleGattServer::handleFirmwareChunk(const uint8_t *data, size_t length) {
  if (length == 0) {
    return;
  }
  if (!firmwareUpdater_.writeChunk(data, length)) {
    Serial.println(
        "BleGattServer: firmware chunk write failed — aborting this update.");
    notifyFirmwareUpdateResult(false, firmwareUpdater_.lastError());
    firmwareUpdater_.abort();
    return;
  }
  firmwareBytesReceived_ += length;
}

void BleGattServer::handleFinishFirmwareUpdate() {
  Serial.println("BleGattServer: FINISH_FIRMWARE_UPDATE received.");
  if (!firmwareUpdater_.inProgress()) {
    Serial.println(
        "BleGattServer: FINISH_FIRMWARE_UPDATE received but no update is in "
        "progress — ignoring.");
    notifyFirmwareUpdateResult(false, 0xFF);
    return;
  }
  // Checked here too, not just left to Update::end()'s own size check, so a short transfer is
  // never even attempted to finalize — belt-and-suspenders around the exact same "only a fully-
  // received image ever flips the boot partition" guarantee (see BleFirmwareUpdater's header).
  if (firmwareBytesReceived_ != firmwareExpectedSize_) {
    Serial.printf(
        "BleGattServer: FINISH_FIRMWARE_UPDATE received after only %u/%u "
        "bytes — aborting instead of finalizing.\n",
        static_cast<unsigned>(firmwareBytesReceived_),
        static_cast<unsigned>(firmwareExpectedSize_));
    firmwareUpdater_.abort();
    notifyFirmwareUpdateResult(false, 0xFF);
    return;
  }

  const bool success = firmwareUpdater_.finish();
  notifyFirmwareUpdateResult(success, success ? 0 : firmwareUpdater_.lastError());
  if (!success) {
    return;
  }

  // Give the notification above (and the phone's GATT stack) a moment to actually go out over
  // the air before the link drops out from under it — ESP.restart() tears down BLE immediately.
  Serial.println("BleGattServer: restarting to boot the new firmware...");
  delay(500);
  ESP.restart();
}

void BleGattServer::notifyFirmwareUpdateResult(bool success, uint8_t errorCode) {
  if (!statusChar_ || !connected_) {
    return;
  }
  uint8_t payload[3] = {Config::kStatusFirmwareUpdateResult,
                        success ? uint8_t{0x01} : uint8_t{0x00}, errorCode};
  const size_t payloadLen = success ? 2 : 3;
  statusChar_->setValue(payload, payloadLen);
  statusChar_->notify(connHandle_);
}

void BleGattServer::notifyUsbDownloadState(bool inProgress) {
  if (!statusChar_ || !connected_) {
    return;
  }
  const uint8_t payload[2] = {Config::kStatusUsbDownloadState,
                              inProgress ? uint8_t{0x01} : uint8_t{0x00}};
  statusChar_->setValue(payload, sizeof(payload));
  statusChar_->notify(connHandle_);
}

void BleGattServer::regeneratePairingPasskey() {
  const uint32_t passkey = generateAndStoreNewPasskey(preferences_);
  NimBLEDevice::setSecurityPasskey(passkey);
  Serial.println("BleGattServer: BLE pairing PIN regenerated via serial signal.");
  printPasskey(passkey);
  Serial.println(
      "BleGattServer: already-bonded phones are unaffected; any *new* "
      "pairing from now on must enter this PIN.");
}
