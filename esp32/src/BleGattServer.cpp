#include "BleGattServer.h"

#include <cstring>

#include <esp_random.h>

#include "Config.h"

namespace {
// Generates a fresh random passkey and persists it — shared by first-ever-boot provisioning
// (loadOrGenerateBlePasskey() below) and the explicit `blepin` serial "regenerate" signal (see
// BleGattServer::regeneratePairingPasskey()). Never a fixed literal baked into source: see
// Config::kPrefsKeyBlePasskey's own comment for why a hardcoded PIN wouldn't actually be a
// secret. esp_random() is a real HWRNG on the ESP32, safe to use directly with no seeding needed.
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
                             OtaManager &otaManager, UsbHidOxHost &usbHost)
    : csvBuffer_(csvBuffer), clockSync_(clockSync),
      storedRecordDownloader_(storedRecordDownloader),
      otaManager_(otaManager), usbHost_(usbHost) {}

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

  case Config::kOpSetWifiCredentials: {
    if (length < 2) {
      break;
    }
    size_t pos = 1;
    const uint8_t ssidLen = data[pos++];
    if (pos + ssidLen > length) {
      break;
    }
    std::string ssid(reinterpret_cast<const char *>(data + pos), ssidLen);
    pos += ssidLen;
    if (pos >= length) {
      break;
    }
    const uint8_t passLen = data[pos++];
    if (pos + passLen > length) {
      break;
    }
    std::string pass(reinterpret_cast<const char *>(data + pos), passLen);
    // Deliberately never log the password — only the SSID and its length.
    Serial.printf("BleGattServer: SET_WIFI_CREDENTIALS received, ssid='%s' "
                 "(password %u bytes).\n",
                 ssid.c_str(), passLen);
    otaManager_.setWifiCredentials(ssid, pass);
    break;
  }

  case Config::kOpEnterOtaMode:
    Serial.println("BleGattServer: ENTER_OTA_MODE received.");
    otaManager_.enterOtaMode();
    break;

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
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
    if (!connected_) {
      Serial.println(
          "BleGattServer: phone disconnected while waiting for the USB "
          "download to finish — abandoning this dump.");
      return;
    }
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

void BleGattServer::regeneratePairingPasskey() {
  const uint32_t passkey = generateAndStoreNewPasskey(preferences_);
  NimBLEDevice::setSecurityPasskey(passkey);
  Serial.println("BleGattServer: BLE pairing PIN regenerated via serial signal.");
  printPasskey(passkey);
  Serial.println(
      "BleGattServer: already-bonded phones are unaffected; any *new* "
      "pairing from now on must enter this PIN.");
}
