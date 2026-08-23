#include "OtaManager.h"

#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <WiFi.h>

#include "Config.h"

OtaManager::OtaManager() = default;

void OtaManager::begin() {
  preferences_.begin(Config::kPrefsNamespace, /*readOnly=*/false);
  // WiFi/OTA are intentionally left OFF here — only ENTER_OTA_MODE (or a
  // direct SET_WIFI_CREDENTIALS write) brings the radio up, per the plan's
  // "WiFi stays off by default" coexistence guidance.
}

void OtaManager::setWifiCredentials(const std::string &ssid,
                                    const std::string &pass) {
  // arduino-esp32's WiFi.begin() persists SSID/password into NVS by default
  // (WiFi.persistent(true) is the default), which is the exact same store
  // WiFiManager's autoConnect() consults to decide "have stored credentials"
  // — so this and the captive portal path can never drift apart.
  WiFi.mode(WIFI_STA);
  WiFi.persistent(true);
  WiFi.begin(ssid.c_str(), pass.c_str());
  // Deliberately not blocking/waiting for connection here: this opcode's
  // job is only to persist credentials, matching PROTOCOL.md's description
  // ("configure WiFi STA credentials for OTA"); ENTER_OTA_MODE is what
  // actually brings the link up for a flashing session.
  WiFi.disconnect(/*wifioff=*/true);
}

void OtaManager::setOtaPasswordFromSerial(const std::string &password) {
  preferences_.putString(Config::kPrefsKeyOtaPassword, password.c_str());
  Serial.println("OtaManager: OTA password updated via serial.");
}

void OtaManager::beginArduinoOta() {
  // isKey() first: Preferences::getString() logs an ESP_LOGE-level "nvs_get_str len fail: ...
  // NOT_FOUND" error whenever the key doesn't exist yet — the expected, common case for any
  // device-pairing that has never provisioned an OTA password at all (most users). isKey()
  // performs the equivalent lookup without logging on a miss; skipping straight to the
  // empty-string fallback here is exactly what getString()'s own default would have produced.
  const String storedPassword = preferences_.isKey(Config::kPrefsKeyOtaPassword)
                                     ? preferences_.getString(Config::kPrefsKeyOtaPassword, "")
                                     : String("");
  if (storedPassword.isEmpty()) {
    // Refuse to start ArduinoOTA without a provisioned password (see the
    // plan's security note in Config.h) — WiFi/mDNS still come up (needed
    // for the captive portal / diagnostics), but network flashing is
    // unavailable until a password is set via the serial debug command.
    Serial.println(
        "OtaManager: no OTA password provisioned (serial debug command "
        "required) — ArduinoOTA not started.");
    return;
  }

  ArduinoOTA.setHostname(Config::kOtaHostname);
  ArduinoOTA.setPassword(storedPassword.c_str());
  // Keep the idle timer from expiring mid-flash: an in-progress OTA session
  // must not be torn down by the same idle timeout that reclaims WiFi after
  // a session that was opened but never used.
  ArduinoOTA.onStart([this]() { lastActivityMs_ = millis(); });
  ArduinoOTA.onProgress(
      [this](unsigned int, unsigned int) { lastActivityMs_ = millis(); });
  ArduinoOTA.begin();
  otaStarted_ = true;
  Serial.printf("OtaManager: ArduinoOTA ready as '%s.local'\n",
               Config::kOtaHostname);
}

void OtaManager::enterOtaMode() {
  lastActivityMs_ = millis();
  if (wifiActive_) {
    return; // Already up; just reset the idle timer (done above).
  }

  wifiManager_.setConfigPortalTimeout(Config::kOtaIdleTimeoutMs / 1000UL);
  const bool connected = wifiManager_.autoConnect(Config::kOtaSetupApName);
  wifiActive_ = connected && WiFi.status() == WL_CONNECTED;
  if (wifiActive_) {
    Serial.printf("OtaManager: WiFi up, IP=%s\n",
                 WiFi.localIP().toString().c_str());
    beginArduinoOta();
  } else {
    Serial.println("OtaManager: WiFi did not come up (captive portal timed "
                   "out or connect failed).");
  }
}

void OtaManager::teardownWifi() {
  if (otaStarted_) {
    ArduinoOTA.end();
    otaStarted_ = false;
  }
  WiFi.disconnect(/*wifioff=*/true);
  wifiActive_ = false;
  Serial.println("OtaManager: idle timeout — WiFi torn down.");
}

void OtaManager::loop() {
  if (!wifiActive_) {
    return;
  }
  if (otaStarted_) {
    ArduinoOTA.handle();
  }
  if (millis() - lastActivityMs_ > Config::kOtaIdleTimeoutMs) {
    teardownWifi();
  }
}
