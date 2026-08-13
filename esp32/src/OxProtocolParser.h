// OxProtocolParser.h — pure sub-command scanner for the PO-400's live-stream
// USB HID INPUT reports.
//
// This header (and its .cpp) deliberately include NOTHING from Arduino or
// ESP-IDF: it operates purely on caller-supplied byte buffers, so it can be
// exercised byte-for-byte in PlatformIO's "native" unit test environment
// without any hardware or framework present (see test/test_ox_protocol_parser/).
//
// Protocol (confirmed by reading the GPLv3 reference project pulseoxdl's
// process_live() in src/pulseoxdl.c — this module ports its scanning logic,
// not its process — see PROTOCOL.md's "PO-400 USB HID protocol" section):
//
//   Each 64-byte INPUT report packs concatenated sub-commands, never crossing
//   a report boundary. Each sub-command starts with sync byte 0xEB:
//     - Waveform   (buf[1]==0x00), 6 bytes — discarded, but must be walked to
//       keep the scanner in sync.
//     - Measurement(buf[1]==0x01), 8 bytes —
//         pr   = (buf[2] & 0x02) ? buf[3] + 0x80 : buf[3]
//         spo2 = buf[4]                      (0x7F == finger-out, skip row)
//       checksum = sum of preceding bytes & 0x7f, must equal the sub-command's
//       last byte.
//   The scan loop ends normally (not an error) on hitting non-0xEB padding.
//
// Deviation from upstream, per the implementation plan: pulseoxdl's CLI tool
// calls exit_error() (aborts the whole process) on a checksum mismatch. A
// long-running embedded relay must not do that — instead, on a mismatch this
// parser resyncs by advancing exactly 1 byte (not the sub-command's nominal
// length) and retries scanning from there, so a single corrupted sub-command
// only costs a few bytes rather than the rest of the report.
#pragma once

#include <cstddef>
#include <cstdint>

struct OxMeasurement {
  uint8_t spo2;      // 0..100, never 0x7F (finger-out rows are not emitted)
  uint8_t pulseRate; // beats per minute
};

// Implement this to receive decoded measurements as OxProtocolParser scans
// reports. Kept as a pure-virtual interface (rather than std::function) so
// this header stays includable from the strictest -fno-rtti/-fno-exceptions
// embedded builds too.
class OxProtocolParserListener {
public:
  virtual ~OxProtocolParserListener() = default;
  virtual void onMeasurement(const OxMeasurement &measurement) = 0;
};

class OxProtocolParser {
public:
  explicit OxProtocolParser(OxProtocolParserListener &listener);

  // Feed one complete HID INPUT report (any length; the real device always
  // sends kHidReportSize/64-byte reports, but the parser does not assume a
  // fixed length so it stays trivially testable with short synthetic buffers).
  // Invokes listener.onMeasurement() zero or more times.
  void parseReport(const uint8_t *data, size_t length);

  // Diagnostics, useful both for unit tests and for a real Serial diagnostic
  // dump: counts accumulate across the parser's whole lifetime.
  uint32_t measurementCount() const { return measurementCount_; }
  uint32_t fingerOutCount() const { return fingerOutCount_; }
  uint32_t resyncCount() const { return resyncCount_; }
  uint32_t waveformCount() const { return waveformCount_; }

private:
  static constexpr uint8_t kSyncByte = 0xEB;
  static constexpr size_t kWaveformLength = 6;
  static constexpr size_t kMeasurementLength = 8;
  static constexpr uint8_t kSpo2FingerOutSentinel = 0x7F;

  static bool checksumOk(const uint8_t *buf, size_t length);

  OxProtocolParserListener &listener_;
  uint32_t measurementCount_ = 0;
  uint32_t fingerOutCount_ = 0;
  uint32_t resyncCount_ = 0;
  uint32_t waveformCount_ = 0;
};
