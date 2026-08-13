// OtaManager.h — WiFiManager-backed credential storage/captive portal plus
// ArduinoOTA lifecycle, per the plan's "OTA firmware updates over WiFi"
// section. WiFi stays off by default and is only brought up on-demand
// (ENTER_OTA_MODE, opcode 0x06, or SET_WIFI_CREDENTIALS), with an idle
// timeout that tears it back down — see loop()'s doc comment.
#pragma once

#include <Preferences.h>
#include <WiFiManager.h>

#include <string>

class OtaManager {
public:
  OtaManager();

  void begin();

  // Call frequently (e.g. every main.cpp loop() iteration) — pumps
  // ArduinoOTA.handle() while WiFi is up, and tears WiFi back down after
  // Config::kOtaIdleTimeoutMs with no active session.
  void loop();

  // PROTOCOL.md opcode 0x06 ENTER_OTA_MODE: connects with stored credentials
  // if present, otherwise brings up the "PulsoxRelay-Setup" captive portal so
  // credentials can be entered from any phone browser. Starts ArduinoOTA
  // once WiFi is up (if an OTA password has been provisioned — see
  // setOtaPasswordFromSerial()). Runs synchronously (WiFiManager's
  // autoConnect() blocks), so callers must not invoke this from a context
  // that must stay responsive (e.g. call it from a dedicated task, not
  // directly from the BLE write callback).
  void enterOtaMode();

  // PROTOCOL.md opcode 0x05 SET_WIFI_CREDENTIALS: writes directly into the
  // same NVS-backed WiFi credential storage WiFiManager itself reads from
  // (arduino-esp32's WiFi.begin() persists credentials by default), so
  // neither configuration path can drift out of sync with the other, per
  // the plan. Does not itself connect or start OTA — ENTER_OTA_MODE (or a
  // later boot's autoConnect) does that.
  void setWifiCredentials(const std::string &ssid, const std::string &pass);

  // Provisions the OTA password. Deliberately NOT reachable from any BLE
  // opcode — per the plan's security note, only a physically-attached
  // serial/USB debug command may set this, so a compromised phone/BLE link
  // alone can never push firmware. main.cpp should wire this to a Serial
  // command handler, never to BleGattServer.
  void setOtaPasswordFromSerial(const std::string &password);

  bool wifiActive() const { return wifiActive_; }

private:
  void beginArduinoOta();
  void teardownWifi();

  WiFiManager wifiManager_;
  Preferences preferences_;
  bool wifiActive_ = false;
  bool otaStarted_ = false;
  unsigned long lastActivityMs_ = 0;
};
