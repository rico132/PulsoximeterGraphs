#include "OtaRollbackGuard.h"

#include <Arduino.h>
#include <esp_ota_ops.h>

#include "Config.h"

namespace {
// Small on purpose: this only needs to absorb one or two unlucky transient failures (e.g. a
// brownout mid-boot) before concluding the image itself is bad, not give a genuinely broken
// build many chances to keep flashing/rebooting the device.
constexpr uint32_t kMaxBootAttempts = 3;
} // namespace

// Arduino-ESP32's own initArduino() (esp32-hal-misc.c) calls this weak hook before setup() ever
// runs, and skips its own immediate, unconditional mark-valid/mark-invalid decision entirely
// when it returns true (see that function's own `if (!verifyRollbackLater())` guard) — so
// OtaRollbackGuard::begin()/confirmHealthy() below become the only things that ever call
// esp_ota_mark_app_valid_cancel_rollback()/esp_ota_mark_app_invalid_rollback_and_reboot() for
// this firmware. Deliberately a free function, not a class member: it overrides a weak C++
// symbol resolved at link time, which only works with a matching signature at global scope.
bool verifyRollbackLater() { return true; }

void OtaRollbackGuard::begin() {
  const esp_partition_t *running = esp_ota_get_running_partition();
  esp_ota_img_states_t state;
  if (esp_ota_get_state_partition(running, &state) != ESP_OK ||
      state != ESP_OTA_IMG_PENDING_VERIFY) {
    // Either a normal boot of an already-confirmed image, or a partition with no OTA rollback
    // state recorded at all (e.g. a dev build flashed directly over USB/serial, never through
    // Update.h) — nothing to gate in either case.
    return;
  }

  preferences_.begin(Config::kPrefsNamespace, /*readOnly=*/false);
  const uint32_t attempts = preferences_.getUInt(Config::kPrefsKeyOtaBootAttempts, 0) + 1;

  if (attempts > kMaxBootAttempts) {
    Serial.printf(
        "OtaRollbackGuard: new firmware failed to confirm healthy across %u boot attempt(s) — "
        "rolling back to the previous firmware.\n",
        static_cast<unsigned>(attempts - 1));
    preferences_.putUInt(Config::kPrefsKeyOtaBootAttempts, 0);
    // Reboots immediately into the previously-running (already-known-good) partition; never
    // returns on success. Only fails if there's no valid alternate partition to roll back to
    // (e.g. this is the very first image this device has ever run) — logged, not fatal, since
    // falling through just continues booting this image the same as any other boot would.
    const esp_err_t err = esp_ota_mark_app_invalid_rollback_and_reboot();
    Serial.printf("OtaRollbackGuard: rollback failed (%s) — continuing to boot this image.\n",
                 esp_err_to_name(err));
    return;
  }

  Serial.printf(
      "OtaRollbackGuard: booting a newly-updated, not-yet-confirmed firmware (attempt %u/%u).\n",
      static_cast<unsigned>(attempts), static_cast<unsigned>(kMaxBootAttempts));
  preferences_.putUInt(Config::kPrefsKeyOtaBootAttempts, attempts);
  pending_ = true;
}

void OtaRollbackGuard::confirmHealthy() {
  if (!pending_) {
    return;
  }
  pending_ = false;
  preferences_.putUInt(Config::kPrefsKeyOtaBootAttempts, 0);
  esp_ota_mark_app_valid_cancel_rollback();
  Serial.println(
      "OtaRollbackGuard: firmware booted successfully — confirmed valid, rollback cancelled.");
}
