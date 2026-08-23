// CsvRowFormatter.h — fast, incrementally-cached CSV row formatting, shared
// by RamCsvBuffer and FileCsvBuffer (which otherwise have nothing in common
// — see their own begin()/clear()). Deliberately pure C++ (no Arduino/IDF
// dependency — only <ctime>'s gmtime_r(), standard on both the ESP32 and
// PlatformIO's native test env) so this calendar-arithmetic-heavy piece can
// actually be exercised byte-for-byte in the native test env
// (test/test_csv_row_formatter/), unlike the rest of either buffer, which
// needs real Arduino/LittleFS/PSRAM behavior no native test can reach.
//
// Why this exists: StoredRecordDownloader::appendDecodedRecord() calls
// appendRow() once per datum — thousands of times per stored record, always
// with epochSeconds exactly one second after the previous call within that
// record (its loop is literally `startEpochSeconds + i`). The straight-line
// "gmtime_r() + snprintf() every single row" this replaced redid a full
// calendar-from-epoch computation from scratch on every call, and paid
// snprintf()'s real per-call overhead (format-string parsing, va_arg
// handling) for it, even though 99%+ of consecutive rows only ever need
// their seconds field incremented by one. This class instead keeps the last
// row's broken-down (year..second) fields cached and ticks them forward by
// one second directly (a handful of integer comparisons) whenever the new
// epoch is exactly the previous one plus one; gmtime_r() only runs again on
// a genuine jump (a new record's first row, or after a gap) — as rare as
// once per thousands of calls in practice.
#pragma once

#include <cstddef>
#include <cstdint>

class CsvRowFormatter {
public:
  // Formats one row — "YYYY-MM-DD, HH:MM:SS, <spo2>, <pulse>\r\n" — into
  // buf (must be at least Config::kMaxCsvRowLength bytes; the actual
  // maximum this ever writes is 32, same worst case as the snprintf()
  // version this replaced) and returns the number of bytes written. No
  // timezone conversion, same as before: epochSeconds is rendered via
  // gmtime_r() (UTC) exactly as it arrived from ClockSync, per PROTOCOL.md.
  //
  // Instances are meant to be reused call after call across one record's
  // whole datum stream (one instance per ICsvBuffer implementation, held
  // for its own lifetime) — reusing an instance across genuinely
  // discontinuous epochs (a new record, or after a gap) is still fully
  // correct, just falls back to the non-incremental gmtime_r() path for
  // that one call instead of the fast one.
  size_t format(int64_t epochSeconds, uint8_t spo2, uint8_t pulseRate,
               char *buf, size_t bufSize);

private:
  void recomputeFromEpoch(int64_t epochSeconds);
  void advanceOneSecond();
  static bool isLeapYear(int year);
  static int daysInMonth(int year, int month); // month is 1-12

  bool hasCached_ = false;
  int64_t cachedEpoch_ = 0;
  int year_ = 0;
  int month_ = 0;  // 1-12
  int day_ = 0;    // 1-31
  int hour_ = 0;   // 0-23
  int minute_ = 0; // 0-59
  int second_ = 0; // 0-59
};
