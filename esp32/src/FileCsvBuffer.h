// FileCsvBuffer.h — LittleFS-backed ICsvBuffer fallback, used only when
// RamCsvBuffer::begin() fails (e.g. a board variant without PSRAM). Slower
// and adds flash wear over long uptimes, but keeps the firmware functional
// rather than refusing to buffer any data at all.
#pragma once

#include <LittleFS.h>

#include "CsvRowFormatter.h"
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
  void flush() override;

private:
  static constexpr const char *kBufferPath = "/csvbuf.dat";
  uint32_t rowCount_ = 0;
  // Opened lazily on the first appendRow() call and kept open across every
  // subsequent one, rather than reopened per row: LittleFS's open() has to
  // re-traverse the file's whole block list to find the append position
  // (cost grows with file size), and close() commits a directory metadata
  // update that can trigger metadata-block compaction — both easily
  // O(log file size) or worse. Paying that on every single datum of every
  // record was, on real hardware, enough to blow past the 30s task
  // watchdog partway through appending one record's ~9800 rows (see
  // appendRow()'s own comment). mutable so the read-only accessors
  // (sizeBytes(), forEachChunk()) can flush it first — defense in depth
  // against being called before flush() explicitly is, since only
  // flush()/close() actually make its buffered writes visible to a
  // separately-opened read handle on the same path.
  mutable File appendFile_;
  // How many more bytes can still be appended before the littlefs partition
  // itself runs low on free space — computed once, from the filesystem's
  // actual free space (LittleFS.totalBytes() - usedBytes(), minus
  // Config::kFilesystemFreeSpaceSafetyMarginBytes), at begin()/clear() time
  // via refreshRemainingCapacity(), then just decremented per append rather
  // than requeried from LittleFS on every single row: usedBytes() walks the
  // filesystem's own metadata, and appendRow() already learned the hard way
  // (see its own comment) what paying a non-O(1) LittleFS cost on every
  // single datum does to the task watchdog.
  size_t remainingCapacityBytes_ = 0;
  void refreshRemainingCapacity();
  // Reused across every appendRow() call for this buffer's whole lifetime —
  // see CsvRowFormatter's own header comment for why that matters (it's
  // what lets consecutive rows within one record skip re-deriving the
  // whole calendar breakdown from scratch).
  CsvRowFormatter csvRowFormatter_;
};
