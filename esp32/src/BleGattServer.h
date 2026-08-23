// BleGattServer.h — NimBLE-Arduino GATT peripheral implementing exactly the
// PROTOCOL.md opcodes/characteristics (see Config.h, which mirrors that file
// verbatim). Chunked notification streaming is sized to the actual
// negotiated per-connection MTU; the REQUEST_DATA dump runs on its own
// FreeRTOS task, signaled from (never run inside) the NimBLE write callback.
#pragma once

#include <NimBLEDevice.h>
#include <Preferences.h>

#include "ClockSync.h"
#include "ICsvBuffer.h"
#include "OtaManager.h"
#include "StoredRecordDownloader.h"
#include "UsbHidOxHost.h"

class BleGattServer {
public:
  BleGattServer(ICsvBuffer &csvBuffer, ClockSync &clockSync,
               StoredRecordDownloader &storedRecordDownloader,
               OtaManager &otaManager, UsbHidOxHost &usbHost);

  void begin();

  // Sets a new BLE pairing PIN (exactly 6 digits) and persists it to NVS —
  // called only from main.cpp's serial debug command, deliberately never
  // reachable via any BLE opcode (same reasoning as
  // OtaManager::setOtaPasswordFromSerial: a not-yet-paired attacker must
  // never be able to set their own known PIN). Takes effect for the *next*
  // pairing attempt; devices already bonded are unaffected (the passkey is
  // only used during the initial pairing handshake, not for already-
  // established encrypted links) — see Config::kPrefsKeyBlePasskey's comment.
  void setPairingPasskeyFromSerial(const std::string &pin);

private:
  // Forwarders rather than direct multiple-inheritance from the NimBLE
  // callback base classes, so BleGattServer's own interface stays simple and
  // these can each hold just the one back-pointer they need.
  class ServerCallbacks : public NimBLEServerCallbacks {
  public:
    explicit ServerCallbacks(BleGattServer &owner) : owner_(owner) {}
    void onConnect(NimBLEServer *server, NimBLEConnInfo &connInfo) override;
    void onDisconnect(NimBLEServer *server, NimBLEConnInfo &connInfo,
                      int reason) override;
    void onMTUChange(uint16_t mtu, NimBLEConnInfo &connInfo) override;

  private:
    BleGattServer &owner_;
  };

  class ControlCallbacks : public NimBLECharacteristicCallbacks {
  public:
    explicit ControlCallbacks(BleGattServer &owner) : owner_(owner) {}
    void onWrite(NimBLECharacteristic *characteristic,
                NimBLEConnInfo &connInfo) override;

  private:
    BleGattServer &owner_;
  };

  static void dataSendTaskTrampoline(void *param);
  void dataSendTaskLoop();
  void handleControlWrite(const uint8_t *data, size_t length);
  void requestDataDump();

  ICsvBuffer &csvBuffer_;
  ClockSync &clockSync_;
  StoredRecordDownloader &storedRecordDownloader_;
  OtaManager &otaManager_;
  UsbHidOxHost &usbHost_;
  Preferences preferences_;

  ServerCallbacks serverCallbacks_{*this};
  ControlCallbacks controlCallbacks_{*this};

  NimBLEServer *server_ = nullptr;
  NimBLECharacteristic *controlChar_ = nullptr;
  NimBLECharacteristic *dataChar_ = nullptr;
  NimBLECharacteristic *statusChar_ = nullptr;

  volatile uint16_t connHandle_ = 0;
  volatile bool connected_ = false;
  SemaphoreHandle_t requestDataSignal_ = nullptr;
};
