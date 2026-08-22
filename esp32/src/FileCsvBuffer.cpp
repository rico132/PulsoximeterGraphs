#include "FileCsvBuffer.h"

#include <ctime>
#include <cstdio>

#include <Arduino.h>
#include <LittleFS.h>
#include <esp_rom_sys.h>

#include "Config.h"

namespace {
// How many of the very first rows received from the PO-400 (live-stream or
// stored-record, whichever arrives first) get echoed to Serial — a quick way
// to eyeball what's actually coming off the device without pulling the BLE
// dump, e.g. while chasing a link-health issue.
constexpr uint32_t kDebugRowsToPrint = 10;

// Duplicated from RamCsvBuffer.cpp rather than shared, deliberately: it's a
// ~10-line formatter and the two buffers otherwise share nothing, so a
// shared header would only add indirection for this one function.
size_t formatCsvRow(int64_t epochSeconds, uint8_t spo2, uint8_t pulseRate,
                    char *buf, size_t bufSize) {
  const time_t t = static_cast<time_t>(epochSeconds);
  struct tm tmVal;
  gmtime_r(&t, &tmVal);
  const int written = snprintf(
      buf, bufSize, "%04d-%02d-%02d, %02d:%02d:%02d, %d, %d\r\n",
      tmVal.tm_year + 1900, tmVal.tm_mon + 1, tmVal.tm_mday, tmVal.tm_hour,
      tmVal.tm_min, tmVal.tm_sec, spo2, pulseRate);
  if (written < 0) {
    return 0;
  }
  return static_cast<size_t>(written) < bufSize ? static_cast<size_t>(written)
                                                : bufSize - 1;
}
} // namespace

FileCsvBuffer::FileCsvBuffer() = default;
FileCsvBuffer::~FileCsvBuffer() = default;

bool FileCsvBuffer::begin() {
  if (!LittleFS.begin(/*formatOnFail=*/true)) {
    return false;
  }
  if (!LittleFS.exists(kBufferPath)) {
    File f = LittleFS.open(kBufferPath, "w");
    if (!f) {
      return false;
    }
    f.close();
  }
  rowCount_ = 0;
  // Recover the row count from whatever is already on disk (e.g. after a
  // reboot with un-synced data still buffered) by counting "\r\n" line
  // terminators rather than trusting any separately-persisted counter.
  File f = LittleFS.open(kBufferPath, "r");
  if (f) {
    int prev = -1;
    while (f.available()) {
      const int c = f.read();
      if (prev == '\r' && c == '\n') {
        rowCount_++;
      }
      prev = c;
    }
    f.close();
  }
  return true;
}

bool FileCsvBuffer::appendRow(int64_t epochSeconds, uint8_t spo2,
                             uint8_t pulseRate) {
  if (isFull()) {
    return false;
  }
  char row[Config::kMaxCsvRowLength];
  const size_t len = formatCsvRow(epochSeconds, spo2, pulseRate, row,
                                  sizeof(row));
  File f = LittleFS.open(kBufferPath, "a");
  if (!f) {
    return false;
  }
  const size_t written = f.write(reinterpret_cast<const uint8_t *>(row), len);
  f.close();
  if (written != len) {
    return false;
  }
  rowCount_++;
  if (rowCount_ <= kDebugRowsToPrint) {
    // esp_rom_printf, not Serial — same reasoning as RamCsvBuffer::appendRow():
    // called back-to-back, once per datum, right after a stored-record
    // decode finishes, on usbTask. `row` is formatCsvRow()'s snprintf()
    // output, so it's already a valid null-terminated C string (including
    // its own trailing "\r\n").
    esp_rom_printf("CsvBuffer: row %u: %s", rowCount_, row);
  }
  return true;
}

size_t FileCsvBuffer::sizeBytes() const {
  File f = LittleFS.open(kBufferPath, "r");
  if (!f) {
    return 0;
  }
  const size_t size = f.size();
  f.close();
  return size;
}

bool FileCsvBuffer::isFull() const {
  return rowCount_ >= Config::kMaxBufferedRows;
}

void FileCsvBuffer::forEachChunk(size_t chunkSize,
                                 const ChunkSink &sink) const {
  if (chunkSize == 0) {
    return;
  }
  File f = LittleFS.open(kBufferPath, "r");
  if (!f) {
    return;
  }
  uint8_t *chunkBuf = new uint8_t[chunkSize];
  while (f.available()) {
    const size_t n = f.read(chunkBuf, chunkSize);
    if (n == 0) {
      break;
    }
    sink(chunkBuf, n);
  }
  delete[] chunkBuf;
  f.close();
}

void FileCsvBuffer::clear() {
  LittleFS.remove(kBufferPath);
  File f = LittleFS.open(kBufferPath, "w");
  if (f) {
    f.close();
  }
  rowCount_ = 0;
}
