#include "BleFirmwareUpdater.h"

#include <Arduino.h>
#include <Update.h>
#include <cctype>
#include <cstring>

namespace {
bool isValidMd5Hex(const char *hex) {
  if (std::strlen(hex) != 32) {
    return false;
  }
  for (int i = 0; i < 32; i++) {
    if (!std::isxdigit(static_cast<unsigned char>(hex[i]))) {
      return false;
    }
  }
  return true;
}
} // namespace

bool BleFirmwareUpdater::begin(size_t totalSize, const char *expectedMd5Hex,
                               BeginResult *outResult) {
  auto setResult = [outResult](BeginResult r) {
    if (outResult) {
      *outResult = r;
    }
  };

  if (inProgress_) {
    setResult(BeginResult::kAlreadyInProgress);
    return false;
  }
  if (totalSize == 0 || !isValidMd5Hex(expectedMd5Hex)) {
    setResult(BeginResult::kBadArgument);
    return false;
  }

  // U_FLASH targets the app OTA partitions (app0/app1) — never U_SPIFFS, which would instead
  // target the littlefs data partition holding the CSV buffer, an entirely different (and here,
  // never intended) kind of "update".
  if (!Update.begin(totalSize, U_FLASH)) {
    Serial.printf("BleFirmwareUpdater: Update.begin(%u) failed: %s\n",
                 static_cast<unsigned>(totalSize), Update.errorString());
    setResult(BeginResult::kUpdateBeginFailed);
    return false;
  }
  if (!Update.setMD5(expectedMd5Hex)) {
    // isValidMd5Hex() above already guarantees the length/charset Update.setMD5() itself
    // checks, so reaching this would mean something changed underneath us — treat as
    // defensive belt-and-suspenders rather than a case expected to actually happen.
    Update.abort();
    setResult(BeginResult::kBadArgument);
    return false;
  }

  inProgress_ = true;
  Serial.printf(
      "BleFirmwareUpdater: update started, expecting %u bytes (MD5 %s).\n",
      static_cast<unsigned>(totalSize), expectedMd5Hex);
  setResult(BeginResult::kOk);
  return true;
}

bool BleFirmwareUpdater::writeChunk(const uint8_t *data, size_t len) {
  if (!inProgress_) {
    return false;
  }
  const size_t written = Update.write(const_cast<uint8_t *>(data), len);
  if (written != len) {
    Serial.printf(
        "BleFirmwareUpdater: write failed (wrote %u/%u bytes): %s\n",
        static_cast<unsigned>(written), static_cast<unsigned>(len),
        Update.errorString());
    return false;
  }
  return true;
}

bool BleFirmwareUpdater::finish() {
  if (!inProgress_) {
    return false;
  }
  inProgress_ = false;
  // evenIfRemaining=false (the default): a short transfer (phone disconnected early, or lied
  // about the size in START_FIRMWARE_UPDATE) fails here rather than being accepted as
  // "complete" with only part of the image actually written — Update::end() itself refuses to
  // flip the boot partition in that case (see this class's own header comment).
  if (!Update.end()) {
    Serial.printf("BleFirmwareUpdater: update failed to finalize: %s\n",
                 Update.errorString());
    return false;
  }
  Serial.println(
      "BleFirmwareUpdater: update finalized successfully — boot partition "
      "switched. Restart to run the new firmware.");
  return true;
}

void BleFirmwareUpdater::abort() {
  if (!inProgress_) {
    return;
  }
  inProgress_ = false;
  Update.abort();
  Serial.println("BleFirmwareUpdater: update aborted — boot partition unchanged.");
}

uint8_t BleFirmwareUpdater::lastError() const { return Update.getError(); }
