// RamCsvBuffer.h — PSRAM-backed ICsvBuffer implementation (primary; see
// FileCsvBuffer for the LittleFS fallback used when no PSRAM is present).
//
// Arduino/ESP-IDF-dependent (heap_caps_malloc with MALLOC_CAP_SPIRAM) — not
// part of the native unit test build.
#pragma once

#include "Config.h"
#include "ICsvBuffer.h"

class RamCsvBuffer : public ICsvBuffer {
public:
  // Allocates a fixed kMaxBufferedBytes arena from PSRAM up front (rather
  // than growing a heap container at runtime) so that a single successful
  // construction guarantees appendRow() never fails from fragmentation later
  // — only from the row-count cap, which is the one designed "full" case.
  // Returns false from begin() if the PSRAM allocation itself fails (e.g.
  // board variant without PSRAM); the caller should then fall back to
  // FileCsvBuffer.
  RamCsvBuffer();
  ~RamCsvBuffer() override;

  bool begin();

  bool appendRow(int64_t epochSeconds, uint8_t spo2,
                uint8_t pulseRate) override;
  uint32_t rowCount() const override { return rowCount_; }
  size_t sizeBytes() const override { return writeOffset_; }
  bool isFull() const override { return rowCount_ >= Config::kMaxBufferedRows; }
  void forEachChunk(size_t chunkSize, const ChunkSink &sink) const override;
  void clear() override;

private:
  uint8_t *arena_ = nullptr;
  size_t arenaCapacity_ = 0;
  size_t writeOffset_ = 0;
  uint32_t rowCount_ = 0;
};
