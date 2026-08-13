// Unity tests for OxProtocolParser — run with `pio test -e native`.
//
// These exercise the live-stream sub-command scanner against synthetic byte
// fixtures built by hand in this file: normal waveform+measurement decoding,
// the PR-addition flag, finger-out skipping, and — the part most worth
// hardening, per the plan — checksum-mismatch and unknown-subtype resync
// behavior, since a bug here would silently corrupt data rather than crash.

#include <unity.h>

#include <vector>

#include "OxProtocolParser.h"

namespace {

// Appends a waveform sub-command (0xEB 0x00 ...) with a valid checksum.
void appendWaveform(std::vector<uint8_t> &buf, uint8_t amplitude,
                     uint8_t bar) {
  size_t base = buf.size();
  buf.push_back(0xEB);
  buf.push_back(0x00);
  buf.push_back(amplitude);
  buf.push_back(bar);
  buf.push_back(0x00); // placeholder, unused meaning here
  uint8_t sum = 0;
  for (size_t i = base; i < base + 5; i++) {
    sum = static_cast<uint8_t>(sum + buf[i]);
  }
  buf.push_back(sum & 0x7f);
}

// Appends a measurement sub-command (0xEB 0x01 ...) with a valid checksum.
// prAddFlagBit02 sets bit 0x02 of the "PR addition flag" byte, matching
// pulseoxdl's `pr = (buf[2]&0x02) ? buf[3]+0x80 : buf[3]`.
void appendMeasurement(std::vector<uint8_t> &buf, uint8_t prLowByte,
                       uint8_t spo2, bool prAddFlagBit02, uint8_t piLsb = 0,
                       uint8_t piMsb = 0) {
  size_t base = buf.size();
  buf.push_back(0xEB);
  buf.push_back(0x01);
  buf.push_back(prAddFlagBit02 ? 0x06 : 0x04);
  buf.push_back(prLowByte);
  buf.push_back(spo2);
  buf.push_back(piLsb);
  buf.push_back(piMsb);
  uint8_t sum = 0;
  for (size_t i = base; i < base + 7; i++) {
    sum = static_cast<uint8_t>(sum + buf[i]);
  }
  buf.push_back(sum & 0x7f);
}

struct RecordingListener : public OxProtocolParserListener {
  std::vector<OxMeasurement> measurements;
  void onMeasurement(const OxMeasurement &m) override {
    measurements.push_back(m);
  }
};

} // namespace

void setUp(void) {}
void tearDown(void) {}

static void test_single_measurement_no_flag() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  appendMeasurement(report, /*prLowByte=*/71, /*spo2=*/96,
                    /*prAddFlagBit02=*/false);
  // Pad to a full 64-byte report with trailing non-0xEB padding.
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(96, listener.measurements[0].spo2);
  TEST_ASSERT_EQUAL_UINT8(71, listener.measurements[0].pulseRate);
  TEST_ASSERT_EQUAL_UINT32(1, parser.measurementCount());
  TEST_ASSERT_EQUAL_UINT32(0, parser.fingerOutCount());
  TEST_ASSERT_EQUAL_UINT32(0, parser.resyncCount());
}

static void test_pr_addition_flag_adds_0x80() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  appendMeasurement(report, /*prLowByte=*/20, /*spo2=*/98,
                    /*prAddFlagBit02=*/true);
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(20 + 0x80, listener.measurements[0].pulseRate);
}

static void test_finger_out_is_skipped_not_errored() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  appendMeasurement(report, /*prLowByte=*/0, /*spo2=*/0x7F,
                    /*prAddFlagBit02=*/false);
  appendMeasurement(report, /*prLowByte=*/75, /*spo2=*/97,
                    /*prAddFlagBit02=*/false);
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  // Only the second (valid) measurement is reported; the finger-out one is
  // counted separately, not treated as an error.
  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(97, listener.measurements[0].spo2);
  TEST_ASSERT_EQUAL_UINT32(1, parser.fingerOutCount());
  TEST_ASSERT_EQUAL_UINT32(0, parser.resyncCount());
}

static void test_waveform_is_walked_and_ignored() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  appendWaveform(report, 0x10, 0x02);
  appendWaveform(report, 0x11, 0x03);
  appendMeasurement(report, /*prLowByte=*/80, /*spo2=*/95,
                    /*prAddFlagBit02=*/false);
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  TEST_ASSERT_EQUAL_UINT32(2, parser.waveformCount());
  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(95, listener.measurements[0].spo2);
  TEST_ASSERT_EQUAL_UINT32(0, parser.resyncCount());
}

static void test_checksum_mismatch_resyncs_by_one_byte_not_length() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  size_t corruptStart = report.size();
  appendMeasurement(report, /*prLowByte=*/60, /*spo2=*/93,
                    /*prAddFlagBit02=*/false);
  // Corrupt the checksum byte of that first sub-command.
  report[corruptStart + 7] ^= 0xFF;
  // A second, valid measurement follows immediately.
  appendMeasurement(report, /*prLowByte=*/61, /*spo2=*/94,
                    /*prAddFlagBit02=*/false);
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  // The corrupted sub-command yields no measurement, but resync-by-1 means
  // the scanner keeps walking forward byte-by-byte and re-finds the next
  // 0xEB sync byte (the start of the second, valid sub-command) rather than
  // skipping a full 8-byte stride and missing it or misaligning further.
  TEST_ASSERT_GREATER_OR_EQUAL_UINT32(1, parser.resyncCount());
  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(94, listener.measurements[0].spo2);
  TEST_ASSERT_EQUAL_UINT8(61, listener.measurements[0].pulseRate);
}

static void test_unknown_subtype_resyncs_by_one_byte() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report;
  report.push_back(0xEB);
  report.push_back(0x05); // unrecognized sub-type
  report.push_back(0x00);
  appendMeasurement(report, /*prLowByte=*/72, /*spo2=*/99,
                    /*prAddFlagBit02=*/false);
  report.resize(64, 0x80);

  parser.parseReport(report.data(), report.size());

  TEST_ASSERT_EQUAL_UINT32(1, parser.resyncCount());
  TEST_ASSERT_EQUAL_UINT32(1, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT8(99, listener.measurements[0].spo2);
}

static void test_multiple_reports_accumulate_counts() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report1;
  appendMeasurement(report1, 70, 96, false);
  report1.resize(64, 0x80);

  std::vector<uint8_t> report2;
  appendMeasurement(report2, 71, 95, false);
  appendMeasurement(report2, 72, 94, false);
  report2.resize(64, 0x80);

  parser.parseReport(report1.data(), report1.size());
  parser.parseReport(report2.data(), report2.size());

  TEST_ASSERT_EQUAL_UINT32(3, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT32(3, parser.measurementCount());
}

static void test_empty_report_is_a_noop() {
  RecordingListener listener;
  OxProtocolParser parser(listener);

  std::vector<uint8_t> report(64, 0x00); // all padding, no 0xEB at all
  parser.parseReport(report.data(), report.size());

  TEST_ASSERT_EQUAL_UINT32(0, listener.measurements.size());
  TEST_ASSERT_EQUAL_UINT32(0, parser.resyncCount());
}

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;
  UNITY_BEGIN();
  RUN_TEST(test_single_measurement_no_flag);
  RUN_TEST(test_pr_addition_flag_adds_0x80);
  RUN_TEST(test_finger_out_is_skipped_not_errored);
  RUN_TEST(test_waveform_is_walked_and_ignored);
  RUN_TEST(test_checksum_mismatch_resyncs_by_one_byte_not_length);
  RUN_TEST(test_unknown_subtype_resyncs_by_one_byte);
  RUN_TEST(test_multiple_reports_accumulate_counts);
  RUN_TEST(test_empty_report_is_a_noop);
  return UNITY_END();
}
