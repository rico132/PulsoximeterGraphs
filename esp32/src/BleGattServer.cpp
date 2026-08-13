#include "BleGattServer.h"

#include <cstring>

#include "Config.h"

BleGattServer::BleGattServer(ICsvBuffer &csvBuffer, ClockSync &clockSync,
                             StoredRecordDownloader &storedRecordDownloader,
                             OtaManager &otaManager)
    : csvBuffer_(csvBuffer), clockSync_(clockSync),
      storedRecordDownloader_(storedRecordDownloader),
      otaManager_(otaManager) {}

void BleGattServer::ServerCallbacks::onConnect(NimBLEServer * /*server*/,
                                               NimBLEConnInfo &connInfo) {
  owner_.connHandle_ = connInfo.getConnHandle();
  owner_.connected_ = true;
}

void BleGattServer::ServerCallbacks::onDisconnect(NimBLEServer * /*server*/,
                                                  NimBLEConnInfo & /*connInfo*/,
                                                  int /*reason*/) {
  // Per the plan: if the phone disconnects mid REQUEST_DATA dump, just stop
  // — since only CLEAR_BUFFER discards data, the next REQUEST_DATA naturally
  // resumes from the same unclaimed buffer, so no resend/ack bookkeeping is
  // needed here.
  owner_.connected_ = false;
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
    // Signal the dedicated send task rather than dumping here — this
    // callback runs on the NimBLE host task and must return quickly.
    xSemaphoreGive(requestDataSignal_);
    break;

  case Config::kOpSetTime: {
    if (length < 9) {
      break;
    }
    int64_t epochSeconds = 0;
    for (int i = 0; i < 8; i++) {
      epochSeconds |= static_cast<int64_t>(data[1 + i]) << (8 * i);
    }
    clockSync_.setTime(epochSeconds);
    break;
  }

  case Config::kOpClearBuffer:
    csvBuffer_.clear();
    break;

  case Config::kOpSetTestMode:
    if (length >= 2) {
      storedRecordDownloader_.setTestMode(data[1] != 0);
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
    otaManager_.setWifiCredentials(ssid, pass);
    break;
  }

  case Config::kOpEnterOtaMode:
    otaManager_.enterOtaMode();
    break;

  default:
    break;
  }
}

void BleGattServer::requestDataDump() {
  if (!connected_ || !dataChar_ || !server_) {
    return;
  }

  uint16_t mtu = server_->getPeerMTU(connHandle_);
  if (mtu == 0) {
    mtu = Config::kMinMtu;
  }
  size_t chunkSize = mtu > 3 ? static_cast<size_t>(mtu - 3) : 20;
  if (chunkSize < 20) {
    chunkSize = 20;
  }

  csvBuffer_.forEachChunk(chunkSize, [this](const uint8_t *data, size_t len) {
    if (!connected_) {
      return; // Phone disconnected mid-dump: just stop (see onDisconnect).
    }
    int attempts = 0;
    while (!dataChar_->notify(data, len, connHandle_) && attempts < 20 &&
          connected_) {
      vTaskDelay(pdMS_TO_TICKS(10));
      attempts++;
    }
  });

  if (connected_) {
    const uint8_t terminator = Config::kEndOfTransferByte;
    dataChar_->notify(&terminator, 1, connHandle_);
  }
}
