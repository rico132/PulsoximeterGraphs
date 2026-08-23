// Unity tests for CsvRowFormatter — run with `pio test -e native`.
//
// Reference epoch/date correspondences below were independently computed via
// `date -u -d "<date>" +%s` (a completely separate implementation from
// gmtime_r()/CsvRowFormatter's own advanceOneSecond()), not derived from any
// code in this repo — so a bug shared between CsvRowFormatter and whatever
// it's checked against can't hide a wrong answer from these tests.

#include <cstring>
#include <string>

#include <unity.h>

#include "CsvRowFormatter.h"

namespace {
std::string formatOne(CsvRowFormatter &formatter, int64_t epoch, uint8_t spo2,
                      uint8_t pulse) {
  char buf[64];
  const size_t len = formatter.format(epoch, spo2, pulse, buf, sizeof(buf));
  return std::string(buf, len);
}
} // namespace

void setUp(void) {}
void tearDown(void) {}

static void test_formats_a_known_row() {
  CsvRowFormatter formatter;
  // 2026-08-15 00:52:18 UTC == epoch 1786755138.
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:18, 98, 75\r\n",
      formatOne(formatter, 1786755138, 98, 75).c_str());
}

static void test_consecutive_rows_increment_the_second() {
  CsvRowFormatter formatter;
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:18, 98, 75\r\n",
      formatOne(formatter, 1786755138, 98, 75).c_str());
  // The very case appendDecodedRecord() always produces: same epoch + 1.
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:19, 97, 76\r\n",
      formatOne(formatter, 1786755139, 97, 76).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:20, 96, 77\r\n",
      formatOne(formatter, 1786755140, 96, 77).c_str());
}

static void test_minute_rollover() {
  CsvRowFormatter formatter;
  // 2026-08-15 00:52:59 UTC == 1786755179; +1s crosses into the next minute.
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:59, 1, 1\r\n",
      formatOne(formatter, 1786755179, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:53:00, 1, 1\r\n",
      formatOne(formatter, 1786755180, 1, 1).c_str());
}

static void test_hour_rollover() {
  CsvRowFormatter formatter;
  // 2026-08-15 00:59:59 UTC == 1786755599; +1s crosses into the next hour.
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:59:59, 1, 1\r\n",
      formatOne(formatter, 1786755599, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 01:00:00, 1, 1\r\n",
      formatOne(formatter, 1786755600, 1, 1).c_str());
}

static void test_day_rollover_in_a_non_leap_february() {
  CsvRowFormatter formatter;
  // 2025-02-28 23:59:59 UTC == 1740787199; 2025 is not a leap year, so the
  // next second must land on March 1st, not a nonexistent February 29th.
  TEST_ASSERT_EQUAL_STRING(
      "2025-02-28, 23:59:59, 1, 1\r\n",
      formatOne(formatter, 1740787199, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2025-03-01, 00:00:00, 1, 1\r\n",
      formatOne(formatter, 1740787200, 1, 1).c_str());
}

static void test_day_rollover_in_a_leap_february() {
  CsvRowFormatter formatter;
  // 2024 IS a leap year: Feb 28 -> Feb 29 (not March 1st) ...
  TEST_ASSERT_EQUAL_STRING(
      "2024-02-28, 23:59:59, 1, 1\r\n",
      formatOne(formatter, 1709164799, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2024-02-29, 00:00:00, 1, 1\r\n",
      formatOne(formatter, 1709164800, 1, 1).c_str());
  // ... and only *then*, a full day later, into March 1st.
  TEST_ASSERT_EQUAL_STRING(
      "2024-02-29, 23:59:59, 1, 1\r\n",
      formatOne(formatter, 1709251199, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2024-03-01, 00:00:00, 1, 1\r\n",
      formatOne(formatter, 1709251200, 1, 1).c_str());
}

static void test_year_rollover() {
  CsvRowFormatter formatter;
  // 2025-12-31 23:59:59 UTC == 1767225599; +1s crosses into 2026.
  TEST_ASSERT_EQUAL_STRING(
      "2025-12-31, 23:59:59, 1, 1\r\n",
      formatOne(formatter, 1767225599, 1, 1).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-01-01, 00:00:00, 1, 1\r\n",
      formatOne(formatter, 1767225600, 1, 1).c_str());
}

// A jump (a new record starting, or a gap) must still produce the right
// answer via the non-incremental gmtime_r() fallback, not just crash or
// silently reuse stale cached fields from a previous, unrelated call.
static void test_non_consecutive_jump_still_recomputes_correctly() {
  CsvRowFormatter formatter;
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:18, 98, 75\r\n",
      formatOne(formatter, 1786755138, 98, 75).c_str());
  // Jumps clean across the leap-year day rollover tested above, on the same
  // instance -- exercising that the "was the last call exactly epoch-1"
  // check correctly falls back here instead of incrementing from stale state.
  TEST_ASSERT_EQUAL_STRING(
      "2024-02-29, 00:00:00, 1, 1\r\n",
      formatOne(formatter, 1709164800, 1, 1).c_str());
}

// spo2/pulse use plain "%d" formatting (no leading zeros, variable width) —
// confirm the hand-written digit writer matches that for every digit count
// a uint8_t can produce (0, 1, 2, and 3 digits).
static void test_spo2_and_pulse_have_no_leading_zeros() {
  CsvRowFormatter formatter;
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:18, 0, 0\r\n",
      formatOne(formatter, 1786755138, 0, 0).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:19, 9, 5\r\n",
      formatOne(formatter, 1786755139, 9, 5).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:20, 100, 45\r\n",
      formatOne(formatter, 1786755140, 100, 45).c_str());
  TEST_ASSERT_EQUAL_STRING(
      "2026-08-15, 00:52:21, 255, 255\r\n",
      formatOne(formatter, 1786755141, 255, 255).c_str());
}

// The whole point of the incremental path: running many consecutive seconds
// through one reused instance must match what a brand-new instance (forced
// onto the non-incremental gmtime_r() path every single call, since it never
// has a matching cached epoch) produces for each of those same epochs,
// spanning several of the rollovers exercised individually above.
static void test_incremental_path_matches_fresh_recompute_every_second() {
  CsvRowFormatter incremental;
  const int64_t start = 1709164795; // 2024-02-28 23:59:55 UTC
  const int64_t end = 1709251205;   // a few seconds into 2024-03-01
  for (int64_t epoch = start; epoch <= end; epoch++) {
    CsvRowFormatter fresh; // never sees a matching cached epoch -> always recomputes
    TEST_ASSERT_EQUAL_STRING(formatOne(fresh, epoch, 42, 84).c_str(),
                             formatOne(incremental, epoch, 42, 84).c_str());
  }
}

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;
  UNITY_BEGIN();
  RUN_TEST(test_formats_a_known_row);
  RUN_TEST(test_consecutive_rows_increment_the_second);
  RUN_TEST(test_minute_rollover);
  RUN_TEST(test_hour_rollover);
  RUN_TEST(test_day_rollover_in_a_non_leap_february);
  RUN_TEST(test_day_rollover_in_a_leap_february);
  RUN_TEST(test_year_rollover);
  RUN_TEST(test_non_consecutive_jump_still_recomputes_correctly);
  RUN_TEST(test_spo2_and_pulse_have_no_leading_zeros);
  RUN_TEST(test_incremental_path_matches_fresh_recompute_every_second);
  return UNITY_END();
}
