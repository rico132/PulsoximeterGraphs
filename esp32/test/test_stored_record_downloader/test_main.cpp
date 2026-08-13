// Unity tests for StoredRecordDecode::decodeAuto/decodeManual — run with
// `pio test -e native`.
//
// These validate the ported nibble-decoding algorithm byte-for-byte against
// the pulseoxdl reference project's own captured fixtures: raw USB HID
// datums-command transmissions (fixtures/*.hidraw, copied verbatim from that
// project's data/test/transmission/{auto,manual}/{spo2,pr}) and that
// project's own decoded CSV output for the same input
// (fixtures/*_expected.csv, copied from data/test/manufacturer/{auto,manual}/
// csv) — which the reference tool's own test.sh treats as ground truth. A
// byte-for-byte match here is the strongest possible confirmation that this
// is a faithful, correct port rather than a paraphrase.

#include <unity.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "StoredRecordDownloader.h"

using StoredRecordDecode::AutoState;
using StoredRecordDecode::DecodeResult;
using StoredRecordDecode::HidReportSource;
using StoredRecordDecode::ManualState;
using StoredRecordDecode::kArtifactPr;
using StoredRecordDecode::kArtifactSpo2;

namespace {

std::vector<uint8_t> readWholeFile(const std::string &path) {
  std::vector<uint8_t> data;
  FILE *f = fopen(path.c_str(), "rb");
  if (!f) {
    return data;
  }
  fseek(f, 0, SEEK_END);
  const long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (size > 0) {
    data.resize(static_cast<size_t>(size));
    const size_t n = fread(data.data(), 1, data.size(), f);
    data.resize(n);
  }
  fclose(f);
  return data;
}

// Feeds 64-byte reports sequentially from an in-memory buffer that already
// is the raw concatenation of HID reports (exactly what the fixture files
// contain, and exactly what a real UsbHidOxHost::readReport() stream would
// look like too).
class BufferReportSource : public HidReportSource {
public:
  explicit BufferReportSource(std::vector<uint8_t> data)
      : data_(std::move(data)) {}

  bool readReport(uint8_t report[64]) override {
    if (offset_ + 64 > data_.size()) {
      return false;
    }
    memcpy(report, data_.data() + offset_, 64);
    offset_ += 64;
    return true;
  }

private:
  std::vector<uint8_t> data_;
  size_t offset_ = 0;
};

struct ExpectedRow {
  int spo2;
  int pulse;
};

std::vector<ExpectedRow> parseExpectedCsv(const std::string &path) {
  std::vector<ExpectedRow> rows;
  FILE *f = fopen(path.c_str(), "r");
  if (!f) {
    return rows;
  }
  char line[256];
  bool first = true;
  while (fgets(line, sizeof(line), f)) {
    if (first) { // Skip "DATE,TIME,SPO2,PULSE" header.
      first = false;
      continue;
    }
    // Row format: "YYYY-MM-DD, HH:MM:SS, <spo2>, <pulse>\r\n" — the date
    // field's own hyphens don't collide with the ", " field separators, so
    // splitting on ", " is sufficient without a full CSV parser.
    int spo2 = 0, pulse = 0;
    // Find the last two comma-separated numeric fields.
    std::string s(line);
    size_t p3 = s.rfind(", ");
    if (p3 == std::string::npos) {
      continue;
    }
    pulse = atoi(s.c_str() + p3 + 2);
    size_t p2 = s.rfind(", ", p3 - 1);
    if (p2 == std::string::npos) {
      continue;
    }
    spo2 = atoi(s.c_str() + p2 + 2);
    rows.push_back({spo2, pulse});
  }
  fclose(f);
  return rows;
}

const std::string kFixturesDir = "test/test_stored_record_downloader/fixtures/";

} // namespace

void setUp(void) {}
void tearDown(void) {}

static void test_decode_auto_matches_reference_byte_for_byte() {
  const std::vector<ExpectedRow> expected =
      parseExpectedCsv(kFixturesDir + "auto_expected.csv");
  TEST_ASSERT_EQUAL_UINT32(10425, expected.size());

  BufferReportSource spo2Source(
      readWholeFile(kFixturesDir + "auto_spo2.hidraw"));
  BufferReportSource prSource(readWholeFile(kFixturesDir + "auto_pr.hidraw"));

  // Both SpO2 and PR decodes of one record share the same AutoState — see
  // AutoState's doc comment on why upstream's "top"/"first" persist across
  // them rather than resetting.
  AutoState state;
  std::vector<uint8_t> spo2, pr;
  const DecodeResult spo2Result = StoredRecordDecode::decodeAuto(
      spo2Source, static_cast<uint32_t>(expected.size()), state, spo2);
  const DecodeResult prResult = StoredRecordDecode::decodeAuto(
      prSource, static_cast<uint32_t>(expected.size()), state, pr);

  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::Ok),
                       static_cast<int>(spo2Result));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::Ok),
                       static_cast<int>(prResult));
  TEST_ASSERT_EQUAL_UINT32(expected.size(), spo2.size());
  TEST_ASSERT_EQUAL_UINT32(expected.size(), pr.size());

  size_t mismatches = 0;
  for (size_t i = 0; i < expected.size(); i++) {
    if (static_cast<int>(spo2[i]) != expected[i].spo2 ||
        static_cast<int>(pr[i]) != expected[i].pulse) {
      mismatches++;
    }
  }
  TEST_ASSERT_EQUAL_UINT32(0, mismatches);
}

static void test_decode_manual_matches_reference_byte_for_byte() {
  const std::vector<ExpectedRow> expected =
      parseExpectedCsv(kFixturesDir + "manual_expected.csv");
  TEST_ASSERT_EQUAL_UINT32(2050, expected.size());

  BufferReportSource spo2Source(
      readWholeFile(kFixturesDir + "manual_spo2.hidraw"));
  BufferReportSource prSource(
      readWholeFile(kFixturesDir + "manual_pr.hidraw"));

  ManualState state;
  std::vector<uint8_t> spo2, pr;
  const DecodeResult spo2Result = StoredRecordDecode::decodeManual(
      spo2Source, static_cast<uint32_t>(expected.size()), kArtifactSpo2,
      state, spo2);
  // Switching from SpO2 to PR resets the 14-byte group alignment, exactly as
  // StoredRecordDownloader::downloadOneManualRecord() does for the real
  // orchestration path (see extract_datums()'s `mdatumbytesused = 0`).
  state.byteIndexInGroup = 0;
  const DecodeResult prResult = StoredRecordDecode::decodeManual(
      prSource, static_cast<uint32_t>(expected.size()), kArtifactPr, state,
      pr);

  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::Ok),
                       static_cast<int>(spo2Result));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::Ok),
                       static_cast<int>(prResult));
  TEST_ASSERT_EQUAL_UINT32(expected.size(), spo2.size());
  TEST_ASSERT_EQUAL_UINT32(expected.size(), pr.size());

  size_t mismatches = 0;
  for (size_t i = 0; i < expected.size(); i++) {
    if (static_cast<int>(spo2[i]) != expected[i].spo2 ||
        static_cast<int>(pr[i]) != expected[i].pulse) {
      mismatches++;
      if (mismatches <= 5) {
        printf("MANUAL mismatch at row %zu: got (%d,%d) want (%d,%d)\n", i,
              spo2[i], pr[i], expected[i].spo2, expected[i].pulse);
      }
    }
  }
  TEST_ASSERT_EQUAL_UINT32(0, mismatches);
}

static void test_decode_stops_cleanly_on_read_error() {
  // A source that immediately fails to supply any report — decodeAuto must
  // report ReadError rather than crash or loop forever.
  class EmptySource : public HidReportSource {
  public:
    bool readReport(uint8_t[64]) override { return false; }
  };
  EmptySource source;
  AutoState state;
  std::vector<uint8_t> out;
  const DecodeResult result =
      StoredRecordDecode::decodeAuto(source, /*datumCount=*/10, state, out);
  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::ReadError),
                       static_cast<int>(result));
}

static void test_decode_zero_datums_is_a_noop() {
  class UnusedSource : public HidReportSource {
  public:
    bool readReport(uint8_t[64]) override {
      TEST_FAIL_MESSAGE("should never be called for datumCount==0");
      return false;
    }
  };
  UnusedSource source;
  AutoState state;
  std::vector<uint8_t> out;
  const DecodeResult result =
      StoredRecordDecode::decodeAuto(source, /*datumCount=*/0, state, out);
  TEST_ASSERT_EQUAL_INT(static_cast<int>(DecodeResult::Ok),
                       static_cast<int>(result));
  TEST_ASSERT_EQUAL_UINT32(0, out.size());
}

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;
  UNITY_BEGIN();
  RUN_TEST(test_decode_auto_matches_reference_byte_for_byte);
  RUN_TEST(test_decode_manual_matches_reference_byte_for_byte);
  RUN_TEST(test_decode_stops_cleanly_on_read_error);
  RUN_TEST(test_decode_zero_datums_is_a_noop);
  return UNITY_END();
}
