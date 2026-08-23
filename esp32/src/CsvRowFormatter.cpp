#include "CsvRowFormatter.h"

#include <ctime>

namespace {
// Writes `value` as exactly `width` zero-padded decimal digits (value must
// already fit in `width` digits — true for every field this is used on:
// month/day/hour/minute/second are all <=99, and year is always in
// [2000, 2099] per the PO-400's own 2-digit-year wire format, see
// StoredRecordDownloader.cpp's `2000 + meta[...]`). Avoids snprintf()'s
// per-call overhead (format-string parsing, va_arg handling) in what's the
// hottest per-datum loop in the firmware.
void writeFixedWidth(char *&p, int value, int width) {
  for (int i = width - 1; i >= 0; i--) {
    p[i] = static_cast<char>('0' + (value % 10));
    value /= 10;
  }
  p += width;
}

// Writes `value` as plain decimal digits, no leading zeros — matches a bare
// "%d" for a uint8_t (0-255, so 1-3 digits). Returns the number written.
int writeVariableWidth(char *p, uint8_t value) {
  char digits[3];
  int n = 0;
  do {
    digits[n++] = static_cast<char>('0' + (value % 10));
    value = static_cast<uint8_t>(value / 10);
  } while (value != 0);
  for (int i = 0; i < n; i++) {
    p[i] = digits[n - 1 - i];
  }
  return n;
}
} // namespace

bool CsvRowFormatter::isLeapYear(int year) {
  return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
}

int CsvRowFormatter::daysInMonth(int year, int month) {
  static const int kDays[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  if (month == 2 && isLeapYear(year)) {
    return 29;
  }
  return kDays[month - 1];
}

void CsvRowFormatter::recomputeFromEpoch(int64_t epochSeconds) {
  const time_t t = static_cast<time_t>(epochSeconds);
  struct tm civil;
  gmtime_r(&t, &civil);
  year_ = civil.tm_year + 1900;
  month_ = civil.tm_mon + 1;
  day_ = civil.tm_mday;
  hour_ = civil.tm_hour;
  minute_ = civil.tm_min;
  second_ = civil.tm_sec;
}

void CsvRowFormatter::advanceOneSecond() {
  second_++;
  if (second_ < 60) {
    return;
  }
  second_ = 0;
  minute_++;
  if (minute_ < 60) {
    return;
  }
  minute_ = 0;
  hour_++;
  if (hour_ < 24) {
    return;
  }
  hour_ = 0;
  day_++;
  if (day_ <= daysInMonth(year_, month_)) {
    return;
  }
  day_ = 1;
  month_++;
  if (month_ <= 12) {
    return;
  }
  month_ = 1;
  year_++;
}

size_t CsvRowFormatter::format(int64_t epochSeconds, uint8_t spo2,
                               uint8_t pulseRate, char *buf, size_t bufSize) {
  if (hasCached_ && epochSeconds == cachedEpoch_ + 1) {
    advanceOneSecond();
  } else {
    recomputeFromEpoch(epochSeconds);
    hasCached_ = true;
  }
  cachedEpoch_ = epochSeconds;

  // Worst case: "YYYY-MM-DD, HH:MM:SS, 100, 255\r\n" == 32 bytes — same
  // bound the snprintf() version this replaced documented. Callers size buf
  // to Config::kMaxCsvRowLength (40) for headroom; this defensive check
  // only matters if that invariant is ever broken.
  if (bufSize < 32) {
    return 0;
  }

  char *p = buf;
  writeFixedWidth(p, year_, 4);
  *p++ = '-';
  writeFixedWidth(p, month_, 2);
  *p++ = '-';
  writeFixedWidth(p, day_, 2);
  *p++ = ',';
  *p++ = ' ';
  writeFixedWidth(p, hour_, 2);
  *p++ = ':';
  writeFixedWidth(p, minute_, 2);
  *p++ = ':';
  writeFixedWidth(p, second_, 2);
  *p++ = ',';
  *p++ = ' ';
  p += writeVariableWidth(p, spo2);
  *p++ = ',';
  *p++ = ' ';
  p += writeVariableWidth(p, pulseRate);
  *p++ = '\r';
  *p++ = '\n';
  return static_cast<size_t>(p - buf);
}
