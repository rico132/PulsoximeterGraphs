// ClockSync.h — SET_TIME-anchored wall clock for a board with no RTC/NTP.
//
// Pure C++ (no Arduino/IDF includes): the monotonic millisecond source is
// injected via a function pointer rather than calling Arduino's millis()
// directly, so this is exercised in PlatformIO's native unit test env too
// (see test/test_clock_sync/). In firmware, construct with `ClockSync(millis)`
// — Arduino's global millis() already matches MillisFn's signature.
#pragma once

#include <cstdint>

class ClockSync {
public:
  using MillisFn = unsigned long (*)();

  explicit ClockSync(MillisFn millisFn);

  // PROTOCOL.md opcode 0x02 SET_TIME: phone pushes its current Unix epoch
  // seconds. Anchors this object's epoch/millis pair; safe to call again on
  // every reconnect (the spec says the phone does, every connection) — each
  // call re-anchors, which only ever improves accuracy/drift.
  void setTime(int64_t epochSeconds);

  // True once setTime() has been called at least once. Firmware must gate
  // row recording on this (see plan's "Timestamp gating") — a placeholder
  // epoch would silently produce misleading data.
  bool hasBeenSet() const { return hasBeenSet_; }

  // Best current estimate of Unix epoch seconds. Only meaningful once
  // hasBeenSet() is true; returns 0 otherwise.
  int64_t now() const;

private:
  MillisFn millisFn_;
  bool hasBeenSet_ = false;
  int64_t baseEpoch_ = 0;
  unsigned long baseMillis_ = 0;
};
