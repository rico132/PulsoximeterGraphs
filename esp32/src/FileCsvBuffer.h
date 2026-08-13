// FileCsvBuffer.h — LittleFS-backed ICsvBuffer fallback, used only when
// RamCsvBuffer::begin() fails (e.g. a board variant without PSRAM). Slower
// and adds flash wear over long uptimes, but keeps the firmware functional
// rather than refusing to buffer any data at all.
#pragma once

#include "ICsvBuffer.h"

class FileCsvBuffer : public ICsvBuffer {
public:
  FileCsvBuffer();
  ~FileCsvBuffer() override;

  // Mounts LittleFS (formatting it on first-ever boot if needed) and opens/
  // creates the backing buffer file. Returns false if the filesystem itself
  // cannot be mounted — at that point the firmware has no CSV buffer at all,
  // which main.cpp should treat as a fatal init error.
  bool begin();

  bool appendRow(int64_t epochSeconds, uint8_t spo2,
                uint8_t pulseRate) override;
  uint32_t rowCount() const override { return rowCount_; }
  size_t sizeBytes() const override;
  bool isFull() const override;
  void forEachChunk(size_t chunkSize, const ChunkSink &sink) const override;
  void clear() override;

private:
  static constexpr const char *kBufferPath = "/csvbuf.dat";
  uint32_t rowCount_ = 0;
};
