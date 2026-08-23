// BleFirmwareUpdater.h — thin wrapper around Arduino-ESP32's Update library (esp_ota_* under
// the hood) driven entirely from BLE writes, so a phone can flash new firmware without WiFi or
// USB — this is the device's only firmware-update mechanism (a prior WiFiManager/ArduinoOTA
// path has been removed). Uses ESP32's dual-partition OTA mechanism (see partitions_8MB.csv's
// app0/app1 slots): a new image is written entirely into the
// *inactive* partition while the current firmware keeps running from the active one, and
// Update::end()'s own success check is what flips esp_ota_set_boot_partition() to the new
// image — never before, and never at all if verification fails. A device stuck on a bad image
// this way still just means "the write failed, run the same firmware again next boot" — the
// currently-running, already-proven-bootable firmware is never overwritten or displaced except
// by a write that Update itself considers fully valid.
//
// Deliberately does not attempt to hold anything open across a reboot: begin()/write()/finish()
// all happen within one BLE connection's lifetime, and an abandoned update (disconnect, or an
// explicit ABORT) just discards the partial write via Update::abort() — the active partition,
// and hence the boot partition, is completely unaffected either way.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

class BleFirmwareUpdater {
public:
  enum class BeginResult {
    kOk,
    kAlreadyInProgress,
    kBadArgument, // expectedMd5Hex wasn't exactly 32 hex chars, or size was 0.
    kUpdateBeginFailed, // Not enough space in the inactive partition, or one is already active
                        // elsewhere — see Update::begin()'s own doc; call lastError() for detail.
  };

  // False if `data`/`len` couldn't be written (partition full, flash error, or begin() was
  // never called / already finished) — call lastError() for Update's own UPDATE_ERROR_* code.
  // Safe to call repeatedly with small chunks (mirrors StoredRecordDownloader's own "many small
  // writes" pattern) — each call's cost is bounded by len, not by the whole image size.
  bool begin(size_t totalSize, const char *expectedMd5Hex, BeginResult *outResult = nullptr);
  bool writeChunk(const uint8_t *data, size_t len);

  // Verifies the total byte count and MD5 against what begin() was told to expect. Only on
  // success does the underlying Update library flip the boot partition to the newly-written
  // image — see this header's own top comment. Callers must reboot (ESP.restart()) themselves
  // once this returns true and any final status notification has had a chance to go out; this
  // method does not reboot on its own, so a caller can log/report the result first.
  bool finish();

  // Discards whatever has been written so far without touching the boot partition — see this
  // header's own top comment. Safe to call even if no update is in progress (a harmless no-op).
  void abort();

  bool inProgress() const { return inProgress_; }

  // Update.h's own UPDATE_ERROR_* code from the most recent begin()/writeChunk()/finish() call
  // that failed — 0 (UPDATE_ERROR_OK) if the most recent relevant call succeeded.
  uint8_t lastError() const;

private:
  bool inProgress_ = false;
};
