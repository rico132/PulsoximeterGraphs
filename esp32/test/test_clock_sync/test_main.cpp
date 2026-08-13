// Unity tests for ClockSync — run with `pio test -e native`.

#include <unity.h>

#include "ClockSync.h"

namespace {
unsigned long g_fakeMillis = 0;
unsigned long fakeMillis() { return g_fakeMillis; }
} // namespace

void setUp(void) { g_fakeMillis = 0; }
void tearDown(void) {}

static void test_not_set_initially() {
  ClockSync clock(fakeMillis);
  TEST_ASSERT_FALSE(clock.hasBeenSet());
  TEST_ASSERT_EQUAL_INT64(0, clock.now());
}

static void test_set_time_anchors_now() {
  ClockSync clock(fakeMillis);
  g_fakeMillis = 1000;
  clock.setTime(1700000000);
  TEST_ASSERT_TRUE(clock.hasBeenSet());
  TEST_ASSERT_EQUAL_INT64(1700000000, clock.now());
}

static void test_now_advances_with_millis() {
  ClockSync clock(fakeMillis);
  g_fakeMillis = 0;
  clock.setTime(1700000000);
  g_fakeMillis = 5500; // 5.5 s later
  TEST_ASSERT_EQUAL_INT64(1700000005, clock.now());
}

static void test_reset_time_reanchors() {
  ClockSync clock(fakeMillis);
  g_fakeMillis = 0;
  clock.setTime(1700000000);
  g_fakeMillis = 10000;
  clock.setTime(1800000000); // phone reconnects, re-sends SET_TIME
  TEST_ASSERT_EQUAL_INT64(1800000000, clock.now());
  g_fakeMillis = 12000;
  TEST_ASSERT_EQUAL_INT64(1800000002, clock.now());
}

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;
  UNITY_BEGIN();
  RUN_TEST(test_not_set_initially);
  RUN_TEST(test_set_time_anchors_now);
  RUN_TEST(test_now_advances_with_millis);
  RUN_TEST(test_reset_time_reanchors);
  return UNITY_END();
}
