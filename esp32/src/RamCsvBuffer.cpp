#include "RamCsvBuffer.h"

#include <cstring>

#include <Arduino.h>
#include <esp_heap_caps.h>

#include "RomPrintfLock.h"

namespace {
// How many of the very first rows received from the PO-400 (live-stream or
// stored-record, whichever arrives first) get echoed to Serial — a quick way
// to eyeball what's actually coming off the device without pulling the BLE
// dump, e.g. while chasing a link-health issue.
constexpr uint32_t kDebugRowsToPrint = 10;
} // namespace

RamCsvBuffer::RamCsvBuffer() = default;

RamCsvBuffer::~RamCsvBuffer() {
  if (arena_) {
    heap_caps_free(arena_);
  }
}

bool RamCsvBuffer::begin() {
  // Use as much of the largest free PSRAM block as is actually available,
  // minus a safety margin reserved for whatever else might still need to
  // allocate PSRAM later (NimBLE, WiFiManager, OTA — all of which begin()
  // after this call in main.cpp's setup()) — rather than a fixed
  // compile-time size. A hardcoded cap previously left hundreds of KB of
  // real free space unused while still silently discarding rows once hit;
  // see Config::kPsramArenaSafetyMarginBytes' comment for the margin
  // itself, and appendDecodedRecord()'s "CSV buffer full" comment for why
  // silent data loss here matters.
  const size_t largestFreeBlock =
      heap_caps_get_largest_free_block(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (largestFreeBlock <= Config::kPsramArenaSafetyMarginBytes) {
    // No PSRAM (or too little to be worth it) — caller falls back to
    // FileCsvBuffer.
    arenaCapacity_ = 0;
    return false;
  }
  arenaCapacity_ = largestFreeBlock - Config::kPsramArenaSafetyMarginBytes;
  arena_ = static_cast<uint8_t *>(
      heap_caps_malloc(arenaCapacity_, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  if (!arena_) {
    // Allocation failed despite the free-block query above (e.g. another
    // allocation raced in between) — caller falls back to FileCsvBuffer.
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
  const size_t len = csvRowFormatter_.format(epochSeconds, spo2, pulseRate,
                                             row, sizeof(row));
  if (writeOffset_ + len > arenaCapacity_) {
    // Should not happen given the cap sizing, but guard against it rather
    // than overrun the arena.
    return false;
  }
  memcpy(arena_ + writeOffset_, row, len);
  writeOffset_ += len;
  rowCount_++;
  if (rowCount_ <= kDebugRowsToPrint) {
    // esp_rom_printf, not Serial: appendRow() is called back-to-back, once
    // per datum, right after a stored-record decode finishes — e.g. from
    // StoredRecordDownloader::appendDecodedRecord()'s tight loop, on
    // usbTask. Ten consecutive blocking Serial writes with no pacing in
    // between is exactly the UART TX-ring-buffer-starvation hazard already
    // chased down (and fixed) throughout StoredRecordDownloader.cpp — this
    // call site just lived in a different file and was missed. Unlike the
    // old snprintf()-based formatter, CsvRowFormatter::format() doesn't
    // null-terminate `row` (it returns a length instead, precisely so the
    // hot append path above never pays for a byte the CSV data itself
    // doesn't need) — explicitly terminate it here, only for this debug
    // path, since %s below needs it.
    row[len] = '\0';
    LOCKED_ROM_PRINTF("CsvBuffer: row %u: %s", rowCount_, row);
  }
  return true;
}

bool RamCsvBuffer::isFull() const {
  return !arena_ || writeOffset_ + Config::kMaxCsvRowLength > arenaCapacity_;
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
