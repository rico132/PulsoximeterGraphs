#include "RamCsvBuffer.h"

#include <ctime>
#include <cstdio>
#include <cstring>

#include <esp_heap_caps.h>

namespace {
// Formats one CSV row per PROTOCOL.md: "YYYY-MM-DD, HH:MM:SS, <spo2>, <pulse>\r\n".
// No timezone conversion is applied — the epoch value came from the phone's
// SET_TIME and is rendered via gmtime() as-is; PROTOCOL.md documents that no
// timezone information is carried by this format at all.
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

RamCsvBuffer::RamCsvBuffer() = default;

RamCsvBuffer::~RamCsvBuffer() {
  if (arena_) {
    heap_caps_free(arena_);
  }
}

bool RamCsvBuffer::begin() {
  arenaCapacity_ = Config::kMaxBufferedBytes;
  arena_ = static_cast<uint8_t *>(
      heap_caps_malloc(arenaCapacity_, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  if (!arena_) {
    // No PSRAM (or allocation failed) — caller falls back to FileCsvBuffer.
    arenaCapacity_ = 0;
    return false;
  }
  writeOffset_ = 0;
  rowCount_ = 0;
  return true;
}

bool RamCsvBuffer::appendRow(int64_t epochSeconds, uint8_t spo2,
                            uint8_t pulseRate) {
  if (!arena_ || isFull()) {
    return false;
  }
  char row[Config::kMaxCsvRowLength];
  const size_t len = formatCsvRow(epochSeconds, spo2, pulseRate, row,
                                  sizeof(row));
  if (writeOffset_ + len > arenaCapacity_) {
    // Should not happen given the cap sizing, but guard against it rather
    // than overrun the arena.
    return false;
  }
  memcpy(arena_ + writeOffset_, row, len);
  writeOffset_ += len;
  rowCount_++;
  return true;
}

void RamCsvBuffer::forEachChunk(size_t chunkSize,
                                const ChunkSink &sink) const {
  if (!arena_ || chunkSize == 0) {
    return;
  }
  size_t offset = 0;
  while (offset < writeOffset_) {
    const size_t len =
        (writeOffset_ - offset) < chunkSize ? (writeOffset_ - offset)
                                             : chunkSize;
    sink(arena_ + offset, len);
    offset += len;
  }
}

void RamCsvBuffer::clear() {
  writeOffset_ = 0;
  rowCount_ = 0;
}
