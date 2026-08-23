// BleGattServer.h — NimBLE-Arduino GATT peripheral implementing exactly the
// PROTOCOL.md opcodes/characteristics (see Config.h, which mirrors that file
// verbatim). Chunked notification streaming is sized to the actual
// negotiated per-connection MTU; the REQUEST_DATA dump runs on its own
// FreeRTOS task, signaled from (never run inside) the NimBLE write callback.
#pragma once

#include <NimBLEDevice.h>
#include <Preferences.h>

#include "BleFirmwareUpdater.h"
#include "ClockSync.h"
#include "ICsvBuffer.h"
#include "StoredRecordDownloader.h"
#include "UsbHidOxHost.h"

class BleGattServer {
public:
  BleGattServer(ICsvBuffer &csvBuffer, ClockSync &clockSync,
               StoredRecordDownloader &storedRecordDownloader,
               UsbHidOxHost &usbHost);

  void begin();

  // Generates a fresh random BLE pairing PIN and persists it to NVS — called
  // only from main.cpp's bare `blepin` serial debug signal, deliberately
  // never reachable via any BLE opcode (a not-yet-paired attacker must
  // never be able to set their own known PIN). No specific PIN can be
  // requested — same as the very first boot's provisioning, the value is
  // always randomly generated, never operator-chosen, so it can't be a
  // predictable/reused value. Takes effect for the *next* pairing attempt;
  // devices already bonded are unaffected (the passkey is only used during
  // the initial pairing handshake, not for already-established encrypted
  // links) — see Config::kPrefsKeyBlePasskey's comment.
  void regeneratePairingPasskey();

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

  // Separate from ControlCallbacks despite both just forwarding a write: the Firmware
  // characteristic carries the actual image bytes (many writes per update, no opcode byte to
  // parse), while Control carries the small START/FINISH/ABORT commands around it — keeping
  // them as two callback objects mirrors that same split at the characteristic level.
  class FirmwareCallbacks : public NimBLECharacteristicCallbacks {
  public:
    explicit FirmwareCallbacks(BleGattServer &owner) : owner_(owner) {}
    void onWrite(NimBLECharacteristic *characteristic,
                NimBLEConnInfo &connInfo) override;

  private:
    BleGattServer &owner_;
  };

  class StatusCallbacks : public NimBLECharacteristicCallbacks {
  public:
    explicit StatusCallbacks(BleGattServer &owner) : owner_(owner) {}
    void onRead(NimBLECharacteristic *characteristic,
               NimBLEConnInfo &connInfo) override;

  private:
    BleGattServer &owner_;
  };

  static void dataSendTaskTrampoline(void *param);
  void dataSendTaskLoop();
  void handleControlWrite(const uint8_t *data, size_t length);
  void requestDataDump();
  void handleFirmwareChunk(const uint8_t *data, size_t length);
  void handleStartFirmwareUpdate(const uint8_t *data, size_t length);
  void handleFinishFirmwareUpdate();
  void notifyFirmwareUpdateResult(bool success, uint8_t errorCode = 0);

  ICsvBuffer &csvBuffer_;
  ClockSync &clockSync_;
  StoredRecordDownloader &storedRecordDownloader_;
  UsbHidOxHost &usbHost_;
  Preferences preferences_;
  BleFirmwareUpdater firmwareUpdater_;

  ServerCallbacks serverCallbacks_{*this};
  ControlCallbacks controlCallbacks_{*this};
  FirmwareCallbacks firmwareCallbacks_{*this};
  StatusCallbacks statusCallbacks_{*this};

  NimBLEServer *server_ = nullptr;
  NimBLECharacteristic *controlChar_ = nullptr;
  NimBLECharacteristic *dataChar_ = nullptr;
  NimBLECharacteristic *statusChar_ = nullptr;
  NimBLECharacteristic *firmwareChar_ = nullptr;

  // How many bytes writeChunk() has accepted so far for the update currently in progress — the
  // size handleStartFirmwareUpdate() was told to expect, compared against this, is what lets
  // handleFinishFirmwareUpdate() detect a short transfer itself (on top of Update::end()'s own
  // check) and report it without ever calling finish() on an incomplete image.
  size_t firmwareBytesReceived_ = 0;
  size_t firmwareExpectedSize_ = 0;

  volatile uint16_t connHandle_ = 0;
  volatile bool connected_ = false;
  SemaphoreHandle_t requestDataSignal_ = nullptr;
};
