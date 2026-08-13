#include "OxProtocolParser.h"

OxProtocolParser::OxProtocolParser(OxProtocolParserListener &listener)
    : listener_(listener) {}

// Sum of buf[0..length-2], masked to 7 bits, must equal buf[length-1].
// Ported from pulseoxdl.c's checksum(): the running sum there is also kept in
// an 8-bit accumulator that wraps mod 256 on every addition; masking with
// 0x7f at the end is equivalent regardless of when the mod-256 reduction
// happens (256 is a multiple of 128), so accumulating in a uint8_t here is
// intentional, not an oversight.
bool OxProtocolParser::checksumOk(const uint8_t *buf, size_t length) {
  if (length == 0) {
    return false;
  }
  uint8_t sum = 0;
  for (size_t i = 0; i + 1 < length; i++) {
    sum = static_cast<uint8_t>(sum + buf[i]);
  }
  sum &= 0x7f;
  return sum == buf[length - 1];
}

namespace {
// Scans forward from `start` for the next sync byte, returning `length` if
// none remains. Used to implement "resync by advancing 1 byte" as a genuine
// search rather than a single-step retry: after a checksum/unknown-subtype
// failure we don't know how many bytes the corrupted sub-command actually
// occupied, so we walk forward one byte at a time until either a fresh 0xEB
// resumes normal scanning or the report is exhausted — one resync *event* is
// counted per failure (see parseReport), not one per byte skipped here.
size_t findNextSync(const uint8_t *data, size_t length, size_t start) {
  size_t pos = start;
  while (pos < length && data[pos] != 0xEB) {
    pos++;
  }
  return pos;
}
} // namespace

void OxProtocolParser::parseReport(const uint8_t *data, size_t length) {
  size_t pos = 0;
  while (pos < length) {
    if (data[pos] != kSyncByte) {
      // Non-0xEB padding: normal, expected end of this report's sub-commands
      // (this is only reached here right after successfully finishing a
      // prior sub-command, or at the very start of the report — a checksum
      // or unknown-subtype failure below routes through findNextSync()
      // instead of falling through to this check, so it never mistakes
      // corruption debris for legitimate trailing padding).
      break;
    }
    if (pos + 1 >= length) {
      // Truncated: a sync byte with no sub-type byte following it. Nothing
      // useful to do but stop; this only happens with malformed/short test
      // input, never with a real 64-byte report that has room for the
      // shortest (6-byte) sub-command.
      break;
    }

    const uint8_t subType = data[pos + 1];
    if (subType == 0x00) {
      // Waveform sub-command: 6 bytes, discarded except for keeping the
      // scanner in sync and validating its checksum.
      if (pos + kWaveformLength > length) {
        break; // Not enough bytes left to validate; stop rather than guess.
      }
      if (!checksumOk(data + pos, kWaveformLength)) {
        resyncCount_++;
        pos = findNextSync(data, length, pos + 1);
        continue;
      }
      waveformCount_++;
      pos += kWaveformLength;
      continue;
    }

    if (subType == 0x01) {
      // Measurement sub-command: 8 bytes.
      if (pos + kMeasurementLength > length) {
        break;
      }
      if (!checksumOk(data + pos, kMeasurementLength)) {
        resyncCount_++;
        pos = findNextSync(data, length, pos + 1);
        continue;
      }
      const uint8_t *m = data + pos;
      const uint8_t spo2 = m[4];
      if (spo2 == kSpo2FingerOutSentinel) {
        fingerOutCount_++;
      } else {
        const uint8_t pr =
            (m[2] & 0x02) ? static_cast<uint8_t>(m[3] + 0x80) : m[3];
        OxMeasurement measurement{spo2, pr};
        measurementCount_++;
        listener_.onMeasurement(measurement);
      }
      pos += kMeasurementLength;
      continue;
    }

    // Sync byte followed by an unrecognized sub-type: we don't know this
    // sub-command's length, so we can't safely skip it wholesale. Treat it
    // the same as a checksum mismatch — resync by searching forward for the
    // next sync byte — rather than aborting the whole report (upstream's
    // exit_error() equivalent), since a long-running relay must stay alive
    // through a corrupted or unknown byte.
    resyncCount_++;
    pos = findNextSync(data, length, pos + 1);
  }
}
