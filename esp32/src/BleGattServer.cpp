#include "BleGattServer.h"

#include <cstring>

#include "Config.h"

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
  NimBLEDevice::init(Config::kDeviceName);
  NimBLEDevice::setMTU(Config::kPreferredMtu);

  server_ = NimBLEDevice::createServer();
  server_->setCallbacks(&serverCallbacks_);

  NimBLEService *service = server_->createService(Config::kServiceUuid);

  controlChar_ = service->createCharacteristic(
      Config::kControlCharUuid, NIMBLE_PROPERTY::WRITE);
  controlChar_->setCallbacks(&controlCallbacks_);

  dataChar_ = service->createCharacteristic(Config::kDataCharUuid,
                                           NIMBLE_PROPERTY::NOTIFY);

  // Stretch / not-MVP per PROTOCOL.md, but declared for scan-filter/GATT
  // completeness: reports the current buffered row count on read.
  statusChar_ = service->createCharacteristic(
      Config::kStatusCharUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

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

  case Config::kOpResyncFromDevice:
    if (storedRecordDownloader_.downloadInProgress()) {
      // Already effectively doing this (e.g. a real attach is mid-download,
      // or an earlier RESYNC_FROM_DEVICE this same connection already
      // kicked it off) — triggering a second overlapping run would just
      // waste USB airtime re-enumerating records the first run either
      // already re-committed or is about to.
      Serial.println("BleGattServer: RESYNC_FROM_DEVICE received while a "
                     "download is already in progress — ignoring.");
      break;
    }
    Serial.println(
        "BleGattServer: RESYNC_FROM_DEVICE received — forgetting which "
        "records were already committed and re-triggering a fresh USB "
        "download from the still-attached device.");
    storedRecordDownloader_.resetCommittedRecords();
    usbHost_.triggerAttachCallback();
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
