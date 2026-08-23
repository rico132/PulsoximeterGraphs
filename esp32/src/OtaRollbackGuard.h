// OtaRollbackGuard.h — closes a gap in ESP-IDF's app-rollback safety net for a BLE-triggered
// firmware update: BleFirmwareUpdater only verifies that the *transferred bytes* are complete
// and MD5-correct before flipping the boot partition — it has no way to know whether the
// resulting firmware actually boots and runs correctly. Arduino-ESP32's own core otherwise marks
// every freshly-booted OTA image "permanently valid" (cancelling ESP-IDF's rollback protection)
// unconditionally, before setup() even runs (see esp32-hal-misc.c's initArduino(), which calls
// its weak verifyOta() hook — defaulting to `return true` — immediately, with no chance for this
// firmware's own setup() to prove itself first). So a build that's a byte-perfect, fully-verified
// transfer but has its own genuine bug that crashes or hangs during boot would otherwise
// boot-loop into that same bad image forever, with no way to recover except a physical USB
// reflash — BLE never comes up in a crash-looping device to push a fix over.
//
// This class overrides Arduino's verifyRollbackLater() weak hook (see the .cpp) to defer that
// decision entirely to the explicit calls below instead of Arduino's own immediate one:
//  - begin() (called once, as the very first thing in setup(), before any subsystem that could
//    itself crash or hang) checks whether the running partition is still
//    ESP_OTA_IMG_PENDING_VERIFY — i.e. this boot is the first one after a BLE/ArduinoOTA update
//    flipped the boot partition to a new image (a normal boot of an already-confirmed image, or
//    a dev build flashed directly over USB with no OTA rollback state at all, is a no-op here).
//    If pending, it increments a persisted "consecutive unconfirmed boot" counter; once that
//    exceeds kMaxBootAttempts, it concludes the new image can't even complete a boot and
//    immediately rolls back to the previously-running (already-known-good, already
//    BLE-update-capable) image via esp_ota_mark_app_invalid_rollback_and_reboot() — which reboots
//    the device on the spot and never returns.
//  - confirmHealthy() (called once, as the very last thing in setup(), only once every other
//    subsystem has initialized without crashing or hanging the watchdog) marks the image valid
//    and resets the attempt counter, cancelling rollback for that image for good.
//
// Deliberately a boot-time gate only, not a continuous runtime health check: once
// confirmHealthy() has run, this class has nothing further to do for the rest of that boot's
// uptime. A bug that only manifests well after boot (not during setup()) is outside what this
// can catch — that's the same scope every boot-rollback scheme uses (this pattern is standard
// practice for embedded OTA in general, nothing specific to this app), since continuously
// re-verifying "is this device still healthy" for the life of the device is a much broader
// problem than this is trying to solve.
#pragma once

#include <Preferences.h>

class OtaRollbackGuard {
public:
  // Must be called exactly once, as the very first thing in setup() — see this header's own doc
  // for why the ordering (before any other subsystem that could crash/hang) matters. Can itself
  // reboot the device (via esp_ota_mark_app_invalid_rollback_and_reboot()) and never return, if
  // this image has already exhausted its unconfirmed-boot attempts.
  void begin();

  // Must be called exactly once, as the last thing in setup(), only once every other subsystem
  // has finished initializing successfully. A no-op if begin() found nothing pending (i.e. this
  // wasn't the first boot after an OTA update).
  void confirmHealthy();

private:
  Preferences preferences_;
  bool pending_ = false;
};
