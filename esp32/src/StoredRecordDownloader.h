// StoredRecordDownloader.h — Auto/Manual stored-record metadata download and
// nibble-packed datum decoding, ported from the GPLv3-licensed `pulseoxdl`
// reference project (see COPYING and the attribution comment in the .cpp).
//
// This header has two halves, deliberately split by an #ifdef ARDUINO guard:
//
//   1. The `StoredRecordDecode` namespace: the actual ported nibble-decoding
//      algorithm (process_nibbles_auto/process_nibbles_manual and
//      store_datum_auto/store_datum_manual in pulseoxdl.c). It is pure C++
//      operating on caller-supplied byte buffers via a small HidReportSource
//      interface — no Arduino/IDF includes — so it is exercised byte-for-byte
//      in PlatformIO's native test env against pulseoxdl's own captured Auto
//      and Manual fixtures (test/test_stored_record_downloader/). This is the
//      "genuinely intricate bit-packed logic" the plan calls out as most
//      worth porting line-for-line and validating byte-identically.
//
//   2. The `StoredRecordDownloader` class: the USB exchange orchestration
//      (STORED_PRESENT / GET_RECORD_METADATA_* / datum download / delete),
//      which necessarily depends on UsbHidOxHost, ICsvBuffer and Preferences
//      (NVS-backed test-mode flag) and so only compiles under the Arduino
//      framework. Guarding its declaration (and the Arduino-only #includes it
//      needs) keeps this header safely includable from the native test
//      build, which only ever needs half 1.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace StoredRecordDecode {

// --- Auto mode geometry (pulseoxdl.c enum stored_auto) ---------------------
constexpr uint8_t kAutoPacketLen = 30;   // AUTO_RECORD_LEN / LEN_DATUMS_CMD
constexpr uint8_t kAutoDatumsStart = 8;  // AUTO_DATUMS_START
constexpr uint8_t kAutoDatumsEnd = 29;   // AUTO_DATUMS_END; also the checksum index
constexpr uint8_t kAutoSeqLsbIdx = 3;    // AUTO_SEQUENCE_START
constexpr uint8_t kAutoSeqMsbIdx = 4;    // AUTO_SEQUENCE_END
constexpr uint8_t kAutoFlagsLsbIdx = 5;
constexpr uint8_t kAutoFlagsMidIdx = 6;
constexpr uint8_t kAutoFlagsMsbIdx = 7;
constexpr uint8_t kAutoAdjustMultiplicand = 16; // MULTIPLICAND

// --- Manual mode geometry (pulseoxdl.c enum stored_manual) ------------------
constexpr uint8_t kManualPacketLen = 20;  // MANUAL_RECORD_LEN
constexpr uint8_t kManualDatumsStart = 5; // MANUAL_DATUMS_START
constexpr uint8_t kManualDatumsEnd = 19;  // MANUAL_DATUMS_END; also checksum index
constexpr uint8_t kManualSeqLsbIdx = 1;   // MANUAL_SEQUENCE_START
constexpr uint8_t kManualSeqMsbIdx = 2;   // MANUAL_SEQUENCE_END
constexpr uint8_t kManualFlagsLsbIdx = 3;
constexpr uint8_t kManualFlagsMsbIdx = 4;
constexpr uint8_t kManualDatumBytesPerGroup = 14; // MANUAL_DATUM_BYTES_CNT

// enum artifacts in pulseoxdl.c.
constexpr uint8_t kArtifactSpo2 = 127;
constexpr uint8_t kArtifactPr = 255;

// State that upstream keeps as file-static globals across an entire Auto (or
// Manual) mode download session — i.e. NOT reset between records, only where
// pulseoxdl.c itself explicitly resets it (see below). Ported faithfully,
// including that persistence, to keep decoded output byte-identical: since
// records are downloaded back-to-back within one session, whatever state a
// record's PR decode leaves behind is (deliberately, in the reference tool)
// the starting state for the next record's SpO2 decode.
struct AutoState {
  uint8_t top = 0;
  bool first = true; // upstream's "first" toggle for the 2-part adjustment
};

// Manual mode's "datum" running absolute-value tracker and its 14-byte group
// position both persist across a session the same way; `byteIndexInGroup` is
// explicitly reset to 0 by the caller when switching from SpO2 to PR within
// one record (mirroring extract_datums()'s explicit `mdatumbytesused = 0`
// "reset to sync to the absolute value" — see pulseoxdl.c) but NOT otherwise.
struct ManualState {
  uint8_t datum = 0;
  uint32_t byteIndexInGroup = 0;
};

// Supplies one 64-byte HID INPUT report at a time, on demand, exactly as
// pulseoxdl.c's extract_datums() calls read_report(dev) in a loop. A real
// caller backs this with UsbHidOxHost::readReport(); tests back it with an
// in-memory reader over a captured fixture file.
class HidReportSource {
public:
  virtual ~HidReportSource() = default;
  // Fills the first 64 bytes of `report` with the next INPUT report. Returns
  // false on read failure/timeout (StoredRecordDownloader distinguishes this
  // from label/checksum/sequence errors below).
  virtual bool readReport(uint8_t report[64]) = 0;
};

enum class DecodeResult {
  Ok,
  ChecksumError,       // a datums-command packet's checksum didn't match
  SequenceError,       // a datums-command packet arrived out of sequence
  ReadError,           // HidReportSource::readReport() failed
  PacketBudgetExceeded, // read this many valid packets without `remaining`
                        // ever reaching 0 — the device kept streaming
                        // successfully, so this is not a transport failure;
                        // it means datumCount (or the nibble geometry) didn't
                        // match what the device actually sent. Only ever
                        // observable against real hardware, since the native
                        // unit tests' fixtures are captured with a matching
                        // datumCount by construction. See decodeAuto's/
                        // decodeManual's packet-budget comment.
};

// Called every kProgressLogInterval packets (see the .cpp) while a decode is
// in progress, so a caller can surface liveness/progress without the decode
// namespace itself depending on Serial/millis (kept Arduino-free so the
// native unit tests still link without them). Real callers (see
// StoredRecordDownloader.cpp) log packetsRead/remaining; tests pass nullptr.
using ProgressSink =
    std::function<void(uint32_t packetsRead, uint32_t remaining)>;

// Called exactly once, right before decodeAuto/decodeManual returns
// ChecksumError or SequenceError, with the exact offending packet's raw
// bytes (packetLen of them) and the 0-based index (within this decode call)
// of the packet that failed. This is the authoritative diagnostic — unlike
// reconstructing failure context from a caller's own running hex dump of
// raw 64-byte reports (error-prone: a packet can span a report boundary,
// and a long multi-line dump is easy to mangle in transit, e.g. copy/paste
// across a wrapped terminal).
using FailureSink = std::function<void(const uint8_t *packet, uint8_t packetLen,
                                       DecodeResult result,
                                       uint32_t packetIndex)>;

// Ported from process_nibbles_auto()/store_datum_auto() plus the
// extract_datums() packet-reassembly loop specialized for Auto mode. `state`
// must be reused (not reset) across the SpO2 and PR downloads of one Auto
// mode download session — see AutoState's comment above. `out` receives the
// decoded values in chronological order (out[0] == earliest second of the
// record) — the reference implementation's countdown-indexed store buffer is
// provably equivalent to simple in-order appending here (validated against
// pulseoxdl's own fixtures; see the .cpp for why), which is what this does.
//
// Bails out with DecodeResult::PacketBudgetExceeded rather than looping
// forever if `remaining` doesn't reach 0 within a generous packet budget —
// see the .cpp for why this can happen against real (vs. fixture) hardware.
DecodeResult decodeAuto(HidReportSource &source, uint32_t datumCount,
                        AutoState &state, std::vector<uint8_t> &out,
                        const ProgressSink &onProgress = nullptr,
                        const FailureSink &onFailure = nullptr);

// Ported from process_nibbles_manual()/store_datum_manual(), specialized for
// Manual mode. `artifactValue` is kArtifactSpo2 or kArtifactPr depending on
// which measurement is being downloaded (matches extract_datums() setting
// `artifact` before each measurement's download). Same packet-budget bailout
// as decodeAuto above.
DecodeResult decodeManual(HidReportSource &source, uint32_t datumCount,
                          uint8_t artifactValue, ManualState &state,
                          std::vector<uint8_t> &out,
                          const ProgressSink &onProgress = nullptr,
                          const FailureSink &onFailure = nullptr);

} // namespace StoredRecordDecode

#ifdef ARDUINO

#include <Preferences.h>

#include "ClockSync.h"
#include "ICsvBuffer.h"
#include "UsbHidOxHost.h"

// Orchestrates the full "download stored records, convert to CSV, maybe
// delete" flow described in the plan's "Stored-record download + test mode"
// section. Runs on UsbHidOxHost's USB task on every PO-400 attach — this is
// now the only thing the USB task does per attach (there is no live-stream
// mode to fall through to; the device's own interrupt endpoint is entirely
// dedicated to this exchange while attached).
class StoredRecordDownloader {
public:
  StoredRecordDownloader(UsbHidOxHost &usbHost, ICsvBuffer &csvBuffer,
                         ClockSync &clockSync);

  // Loads the persisted test-mode flag from NVS (default ON — see
  // Config::kDefaultTestMode) and reads it back for the BLE server's
  // SET_TEST_MODE opcode handler / status mirroring.
  void begin();
  bool testModeEnabled() const;
  void setTestMode(bool enabled); // persists to NVS immediately

  // True for the whole duration of a downloadAndMaybeDelete() call. Lets
  // BleGattServer's REQUEST_DATA handler wait for an in-flight USB download
  // to finish before dumping, rather than sending whatever partial subset
  // of records happens to be buffered at the exact moment the phone's sync
  // button was pressed — which otherwise requires pressing sync once per
  // record as each one finishes downloading and gets appended.
  bool downloadInProgress() const { return downloadInProgress_; }

  // Performs the initial per-attach handshake (see Config::kCmdStopSendingData's
  // comment for why this is required), then sends STORED_PRESENT and, if any
  // record(s) are present, downloads and decodes every one of them (Auto or
  // Manual, per the device's reported mode), appending decoded rows to
  // csvBuffer, then deletes each record from the device unless
  // testModeEnabled() is true. Safe to call whether or not any records are
  // actually present (STORED_PRESENT reports that). Returns false only on a
  // transport-level failure talking to the device (e.g. it was unplugged
  // mid-download).
  bool downloadAndMaybeDelete();

private:
  // Sends a fixed write command (zero-padded to 64 bytes, checksum appended
  // at writeChecksumLen-1 if writeChecksumLen>0) and reads back one 64-byte
  // response, validating its checksum (at readChecksumLen-1) if
  // readChecksumLen>0, then copying its first readLen bytes into readBuf.
  // `expectedFirstByte`, if non-zero, is checked against the response's
  // first byte before the checksum (mirrors pulseoxdl's own read_data():
  // "if (in[0] != io.data[0]) exit_error(...)") — catches a
  // desynced/misaligned read immediately and unambiguously, rather than as
  // a confusing checksum mismatch one exchange later. 0 skips the check
  // (no real response in this protocol starts with 0x00).
  bool sendExchange(const uint8_t *writeData, size_t writeLen,
                    uint8_t writeChecksumLen, uint8_t *readBuf,
                    size_t readLen, uint8_t readChecksumLen,
                    uint8_t expectedFirstByte = 0);
  // Discards any report(s) already sitting in UsbHidOxHost's queue before
  // the handshake below sends its first command. The PO-400 streams its own
  // live data by default until STOP_SENDING_DATA takes effect, so even the
  // brief task-switch after the attach callback fires can leave one or more
  // stray reports queued ahead of anything we're about to request — left
  // alone, every exchange in performInitialHandshake()/downloadAndMaybeDelete()
  // would read back the *previous* command's response instead of its own.
  void drainStaleReports();
  // STOP_SENDING_DATA / two unidentified status queries / an opportunistic
  // clock sync (only when clockSync_.hasBeenSet()) / USER_NAME / MODEL_NAME
  // — see Config::kCmdStopSendingData's comment. Returns false if any of the
  // non-optional steps fails; a failed clock sync alone is logged but not
  // fatal, since pulseoxdl's own comment calls that step "probably not
  // strictly necessary."
  bool performInitialHandshake();
  // Sends a datum-download request command (no response read here — the
  // response is the stream of datums-command HID reports consumed by
  // StoredRecordDecode::decodeAuto/decodeManual instead).
  bool sendRequest(const uint8_t *writeData, size_t writeLen,
                  uint8_t writeChecksumLen);
  bool downloadOneAutoRecord(uint8_t recordIndex, int64_t startEpochSeconds,
                             uint32_t datumCount,
                             StoredRecordDecode::AutoState &autoState);
  bool downloadOneManualRecord(int64_t startEpochSeconds, uint32_t datumCount,
                               StoredRecordDecode::ManualState &manualState);
  void appendDecodedRecord(int64_t startEpochSeconds,
                           const std::vector<uint8_t> &spo2,
                           const std::vector<uint8_t> &pr);

  UsbHidOxHost &usbHost_;
  ICsvBuffer &csvBuffer_;
  ClockSync &clockSync_;
  Preferences preferences_;
  bool testModeEnabled_ = true;
  volatile bool downloadInProgress_ = false;
};

#endif // ARDUINO
