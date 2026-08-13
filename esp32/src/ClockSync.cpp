#include "ClockSync.h"

ClockSync::ClockSync(MillisFn millisFn) : millisFn_(millisFn) {}

void ClockSync::setTime(int64_t epochSeconds) {
  baseEpoch_ = epochSeconds;
  baseMillis_ = millisFn_ ? millisFn_() : 0;
  hasBeenSet_ = true;
}

int64_t ClockSync::now() const {
  if (!hasBeenSet_) {
    return 0;
  }
  const unsigned long nowMillis = millisFn_ ? millisFn_() : baseMillis_;
  // unsigned long subtraction wraps correctly across a millis() overflow
  // (~49.7 days) as long as the elapsed span itself is under ~49.7 days,
  // which holds here since the phone is expected to reconnect/re-anchor far
  // more often than that.
  const unsigned long elapsedMillis = nowMillis - baseMillis_;
  return baseEpoch_ + static_cast<int64_t>(elapsedMillis / 1000UL);
}
