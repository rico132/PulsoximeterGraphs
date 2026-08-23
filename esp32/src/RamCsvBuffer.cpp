#include "RamCsvBuffer.h"

#include <Arduino.h>
#include <esp_heap_caps.h>

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
  // Formatted directly into its final place in the arena rather than into a
  // stack buffer and then memcpy()'d over — isFull() just confirmed at
  // least kMaxCsvRowLength bytes are free here, which CsvRowFormatter never
  // exceeds, so there's nothing left for a separate bounds check on the
  // result to actually catch; skipping the intermediate copy removes a
  // redundant full-row copy from what's the hottest per-datum loop in the
  // firmware (StoredRecordDownloader::appendDecodedRecord(), called once
  // per datum — thousands of times per stored record).
  const size_t len = csvRowFormatter_.format(
      epochSeconds, spo2, pulseRate,
      reinterpret_cast<char *>(arena_ + writeOffset_),
      Config::kMaxCsvRowLength);
  writeOffset_ += len;
  rowCount_++;
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
