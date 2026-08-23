#include "FileCsvBuffer.h"

#include <Arduino.h>
#include <LittleFS.h>

#include "Config.h"

FileCsvBuffer::FileCsvBuffer() = default;
FileCsvBuffer::~FileCsvBuffer() = default;

void FileCsvBuffer::refreshRemainingCapacity() {
  // Actual free space on the partition, minus a safety margin for
  // LittleFS's own metadata/wear-leveling overhead and any other files
  // sharing it (WiFiManager config, OTA staging, ...) — not a fixed
  // compile-time row count. A hardcoded cap previously left hundreds of KB
  // of real free space unused while still silently discarding rows once
  // hit; see appendRow()'s own "CSV buffer full" comment for why that
  // matters, and Config::kFilesystemFreeSpaceSafetyMarginBytes' comment for
  // the margin itself.
  const size_t totalBytes = LittleFS.totalBytes();
  const size_t usedBytes = LittleFS.usedBytes();
  const size_t margin = Config::kFilesystemFreeSpaceSafetyMarginBytes;
  remainingCapacityBytes_ =
      usedBytes + margin >= totalBytes ? 0 : totalBytes - usedBytes - margin;
}

bool FileCsvBuffer::begin() {
  if (!LittleFS.begin(/*formatOnFail=*/true)) {
    return false;
  }
  // Deliberately starts every boot from an empty buffer rather than
  // recovering whatever CSV rows happened to still be on disk from before a
  // reboot: unlike a mid-download crash (recovered from by simply retrying
  // the USB download, since test mode keeps every record on the device —
  // see StoredRecordDownloader::begin()'s matching resetCommittedRecords()
  // call), health data sitting durably in flash across a reboot is no
  // longer something this firmware wants to do, now that a REQUEST_DATA
  // that finds nothing buffered yet will itself trigger a fresh re-download
  // from the still-attached PO-400 (see BleGattServer::requestDataDump()) —
  // nothing is actually lost, only re-fetched.
  clear();
  return true;
}

bool FileCsvBuffer::appendRow(int64_t epochSeconds, uint8_t spo2,
                             uint8_t pulseRate) {
  if (isFull()) {
    return false;
  }
  // Make room in the batch first if this row wouldn't fit — see
  // writeBatch_'s own comment for why batching several dozen rows into one
  // File::write() call is worth doing at all.
  if (writeBatchLen_ + Config::kMaxCsvRowLength > sizeof(writeBatch_)) {
    if (!flushWriteBatchToFile()) {
      return false;
    }
  }
  // Formatted directly into its place in the batch rather than into a
  // separate stack buffer first — same reasoning as RamCsvBuffer::
  // appendRow()'s identical change: the space is already guaranteed
  // available by the check above, so there's nothing left for a copy to
  // protect against.
  const size_t len = csvRowFormatter_.format(
      epochSeconds, spo2, pulseRate,
      reinterpret_cast<char *>(writeBatch_ + writeBatchLen_),
      sizeof(writeBatch_) - writeBatchLen_);
  writeBatchLen_ += len;
  rowCount_++;
  remainingCapacityBytes_ -= len; // safe: isFull() above already ensured
                                  // remainingCapacityBytes_ >= kMaxCsvRowLength >= len
  return true;
}

bool FileCsvBuffer::flushWriteBatchToFile() const {
  if (writeBatchLen_ == 0) {
    return true;
  }
  // Kept open across calls rather than reopened every batch — see
  // appendFile_'s own comment in the header for the full reasoning. Real
  // hardware confirmed the open+write+close-per-row version could not get
  // through appending even half of one record's ~9800 rows before a task
  // watchdog abort: appendRow()'s loop (StoredRecordDownloader::
  // appendDecodedRecord()) never feeds the watchdog itself, only
  // UsbHidOxHost::readReport() does, so there was nothing bounding how long
  // LittleFS's per-open/close cost (which grows with the file's own size)
  // was allowed to run.
  if (!appendFile_) {
    appendFile_ = LittleFS.open(kBufferPath, "a");
    if (!appendFile_) {
      return false;
    }
  }
  const size_t written = appendFile_.write(writeBatch_, writeBatchLen_);
  if (written != writeBatchLen_) {
    return false;
  }
  writeBatchLen_ = 0;
  // No periodic flush()/sync() here — see StoredRecordDownloader.cpp's
  // ScopedCsvFlush, which unconditionally flushes once the whole download
  // session ends (success or failure), for what actually guarantees every
  // appended row is durable/visible. A periodic mid-download flush used to
  // exist to bound how much an ESP32 crash mid-download could cost — flush()
  // (like close(), see appendFile_'s own comment) commits a directory
  // metadata update that can trigger metadata-block compaction, easily
  // O(log file size) or worse, so paying it periodically across tens of
  // thousands of datums added up to real, measurable time on boards without
  // PSRAM (FileCsvBuffer, not RamCsvBuffer, is what's actually in use
  // there). That crash-safety trade-off isn't worth as much as it used to
  // be now that a crash/reboot mid-download is already an accepted,
  // ordinary case (see begin()'s own comment): the next attach just
  // re-downloads everything fresh from the still-attached PO-400 either
  // way, whether zero rows or most of them made it into this buffer
  // beforehand.
  return true;
}

void FileCsvBuffer::flush() {
  flushWriteBatchToFile();
  if (appendFile_) {
    appendFile_.flush();
  }
}

size_t FileCsvBuffer::sizeBytes() const {
  // Defense in depth: a separately-opened read handle only sees what's
  // actually been written+synced to LittleFS, not whatever's sitting
  // staged in writeBatch_ or buffered-but-unflushed in appendFile_ itself
  // (see their own header comments). Callers are expected to have already
  // called flush() — this just avoids returning a stale (too-small) size
  // if one hasn't.
  flushWriteBatchToFile();
  if (appendFile_) {
    appendFile_.flush();
  }
  File f = LittleFS.open(kBufferPath, "r");
  if (!f) {
    return 0;
  }
  const size_t size = f.size();
  f.close();
  return size;
}

bool FileCsvBuffer::isFull() const {
  return remainingCapacityBytes_ < Config::kMaxCsvRowLength;
}

void FileCsvBuffer::forEachChunk(size_t chunkSize,
                                 const ChunkSink &sink) const {
  if (chunkSize == 0) {
    return;
  }
  // See sizeBytes()'s comment — same reasoning.
  flushWriteBatchToFile();
  if (appendFile_) {
    appendFile_.flush();
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
  // Close the kept-open append handle before removing the file out from
  // under it — the next appendRow() call reopens it lazily, same as after
  // construction.
  if (appendFile_) {
    appendFile_.close();
  }
  // Discards anything staged but not yet written to appendFile_ — safe,
  // since the file itself is about to be removed anyway.
  writeBatchLen_ = 0;
  LittleFS.remove(kBufferPath);
  File f = LittleFS.open(kBufferPath, "w");
  if (f) {
    f.close();
  }
  rowCount_ = 0;
  refreshRemainingCapacity();
}
