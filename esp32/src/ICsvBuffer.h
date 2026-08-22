// ICsvBuffer.h — interface shared by RamCsvBuffer (PSRAM, primary) and
// FileCsvBuffer (LittleFS, fallback if no PSRAM). Both live-stream data and
// downloaded stored-record data funnel through the same interface/instance —
// from the BLE/phone side they are indistinguishable, just more buffered CSV
// rows (see plan's "Stored-record download" section).
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

class ICsvBuffer {
public:
  virtual ~ICsvBuffer() = default;

  // Formats and appends one CSV row: "YYYY-MM-DD, HH:MM:SS, <spo2>, <pulse>\r\n"
  // (PROTOCOL.md CSV format) for the given Unix epoch-second timestamp.
  // Returns false (row dropped) if the buffer is already at kMaxBufferedRows —
  // per the plan, the MVP behavior on hitting the cap is to simply stop
  // accepting new rows until CLEAR_BUFFER, not FIFO rotation.
  virtual bool appendRow(int64_t epochSeconds, uint8_t spo2,
                         uint8_t pulseRate) = 0;

  virtual uint32_t rowCount() const = 0;
  virtual size_t sizeBytes() const = 0;
  virtual bool isFull() const = 0;

  // Streams all currently-buffered bytes to `sink` in pieces of at most
  // chunkSize bytes each (BleGattServer uses this to notify at the
  // negotiated-MTU chunk size without materializing the whole buffer as one
  // allocation). Does not modify the buffer.
  using ChunkSink = std::function<void(const uint8_t *data, size_t len)>;
  virtual void forEachChunk(size_t chunkSize, const ChunkSink &sink) const = 0;

  // Only ever called after the phone's explicit CLEAR_BUFFER (opcode 0x03),
  // and only once it has durably stored what it received — see PROTOCOL.md's
  // crash-safety note. Discards everything buffered so far.
  virtual void clear() = 0;

  // Forces any buffered-but-not-yet-durable state out to its backing store.
  // A no-op for RamCsvBuffer (nothing but the arena itself, always "durable"
  // for as long as the device stays powered). FileCsvBuffer overrides this:
  // it keeps its LittleFS file handle open across many appendRow() calls
  // rather than reopening it every row (see FileCsvBuffer.h's appendFile_
  // comment for why), syncing only periodically — callers that need every
  // appended row to actually be visible to a fresh read (e.g. before a BLE
  // dump) must call this first rather than assuming appendRow() alone
  // guarantees it.
  virtual void flush() {}
};
