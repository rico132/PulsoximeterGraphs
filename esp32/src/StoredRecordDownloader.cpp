// StoredRecordDownloader.cpp
//
// The nibble-decoding logic in the StoredRecordDecode namespace below is
// ported directly from process_nibbles_auto(), process_nibbles_manual(),
// store_datum_auto() and store_datum_manual() in pulseoxdl.c, part of:
//
//   pulseoxdl — pulse oximetry downloader (Contec CMS50E, USB HID)
//   Copyright (C) 2021-2022, 2024-2025 Donatas Klimašauskas
//   Licensed under the GNU General Public License v3 (or later).
//   https://gitlab.com/dklimasauskas/pulseoxdl (see esp32/COPYING for the
//   full license text)
//
// Ported per the user's explicit GPLv3 licensing decision recorded in the
// implementation plan: esp32/ as a whole is licensed GPLv3 because of this
// ported logic (see esp32/COPYING and PROTOCOL.md's "Licensing" section).
// This is a line-for-line port of the decode algorithm, not a paraphrase —
// validated byte-for-byte against pulseoxdl's own captured Auto and Manual
// fixtures (test/test_stored_record_downloader/, copied from that project's
// data/test/ directory) via `pio test -e native`.

#include "StoredRecordDownloader.h"

#include <cstring>

namespace StoredRecordDecode {

namespace {

// Sum of buf[0..end-1] masked to 7 bits, compared to buf[end]. Accumulating
// in a uint8_t is intentional: the device's own checksum is defined as a
// running sum truncated to 7 bits, and letting the intermediate sum wrap at
// 8 bits before the final `& 0x7f` mask reproduces that truncation exactly
// (matches pulseoxdl.c's own `unsigned char sum` accumulator).
bool checksumOk(const uint8_t *buf, uint8_t end) {
  uint8_t sum = 0;
  for (uint8_t i = 0; i < end; i++) {
    sum = static_cast<uint8_t>(sum + buf[i]);
  }
  return (sum & 0x7f) == buf[end];
}

// Pulls dcmdlength-sized packets out of a small rolling staging buffer fed by
// HidReportSource::readReport(), one 64-byte report at a time. This replaces
// upstream's manual "left/less" report-boundary bookkeeping in
// extract_datums() with a simpler equivalent: since no byte is ever skipped
// on either side of a report boundary (leftover bytes are always carried
// forward whole into the next packet), just appending each new report's
// bytes to a queue and slicing fixed-size packets off its front produces
// exactly the same sequence of packets as upstream's report-at-a-time
// dance — verified by running this exact simplification against pulseoxdl's
// own captured fixtures (0 byte-level mismatches across both Auto and
// Manual samples; see the native unit tests).
class PacketReassembler {
public:
  explicit PacketReassembler(HidReportSource &source) : source_(source) {}

  // Fills `packet` (packetLen bytes) with the next reassembled datums-command
  // packet, reading additional HID reports from `source_` as needed. Returns
  // false if a report read fails.
  bool nextPacket(uint8_t *packet, uint8_t packetLen) {
    while (staging_.size() < packetLen) {
      uint8_t report[64];
      if (!source_.readReport(report)) {
        return false;
      }
      staging_.insert(staging_.end(), report, report + 64);
    }
    memcpy(packet, staging_.data(), packetLen);
    staging_.erase(staging_.begin(), staging_.begin() + packetLen);
    return true;
  }

private:
  HidReportSource &source_;
  std::vector<uint8_t> staging_;
};

// How many packets a decode loop will read while `remaining` fails to reach
// 0, before concluding datumCount/geometry don't match what the device is
// actually sending and bailing out instead of spinning forever. A packet can
// legitimately yield 0 new datums (an all-"top-adjustment" packet in Auto
// mode), so this can't be a tight bound — but it must be *some* bound, since
// every individual readReport() call succeeding is not proof that `remaining`
// will ever reach 0. Chosen generously: normal decoding needs on the order of
// datumCount / (datums-per-packet) packets (single digits to low tens for
// realistic record sizes), so datumCount*4 plus a flat floor comfortably
// covers legitimate decode patterns while still bounding a genuine mismatch.
uint32_t packetBudget(uint32_t datumCount) {
  return datumCount * 4 + 200;
}

// Every kProgressLogInterval packets, tell `onProgress` (if any) how far
// along a decode is — see ProgressSink's comment in the header for why this
// indirection exists instead of calling Serial directly here.
constexpr uint32_t kProgressLogInterval = 20;

} // namespace

DecodeResult decodeAuto(HidReportSource &source, uint32_t datumCount,
                        AutoState &state, std::vector<uint8_t> &out,
                        const ProgressSink &onProgress) {
  out.clear();
  if (datumCount == 0) {
    return DecodeResult::Ok;
  }
  out.reserve(datumCount);

  PacketReassembler reassembler(source);
  uint32_t remaining = datumCount;
  const uint32_t maxPackets = packetBudget(datumCount);
  uint32_t packetsRead = 0;
  uint16_t expectedSeq = 0;
  uint8_t packet[kAutoPacketLen];

  auto store = [&](uint8_t nibbleValue) {
    if (remaining == 0) {
      return;
    }
    // top - nibbleValue, truncated to 8 bits — matches upstream's unsigned
    // char arithmetic in store_datum_auto() exactly.
    out.push_back(static_cast<uint8_t>(state.top - nibbleValue));
    remaining--;
  };

  while (remaining > 0) {
    if (packetsRead >= maxPackets) {
      return DecodeResult::PacketBudgetExceeded;
    }
    if (!reassembler.nextPacket(packet, kAutoPacketLen)) {
      return DecodeResult::ReadError;
    }
    packetsRead++;
    if (onProgress && packetsRead % kProgressLogInterval == 0) {
      onProgress(packetsRead, remaining);
    }
    if (!checksumOk(packet, kAutoDatumsEnd)) {
      return DecodeResult::ChecksumError;
    }
    const uint16_t gotSeq = static_cast<uint16_t>(
        packet[kAutoSeqLsbIdx] + (packet[kAutoSeqMsbIdx] << 7));
    if (expectedSeq != gotSeq) {
      return DecodeResult::SequenceError;
    }
    expectedSeq++;

    uint32_t flags = packet[kAutoFlagsLsbIdx] +
                     (static_cast<uint32_t>(packet[kAutoFlagsMidIdx]) << 7) +
                     (static_cast<uint32_t>(packet[kAutoFlagsMsbIdx]) << 14);

    for (uint8_t i = kAutoDatumsStart; i < kAutoDatumsEnd && remaining > 0;
        i++) {
      const bool flag = (flags & 0x1) != 0;
      flags >>= 1;
      const uint8_t nibbles = packet[i];
      const uint8_t high = static_cast<uint8_t>(nibbles >> 4);
      const uint8_t low = static_cast<uint8_t>(nibbles & 0x0f);

      if (flag && high == 0x7) {
        // 2-part "top" adjustment, ported verbatim from process_nibbles_auto:
        // the first half sets the high byte (top = 16 * low), the second
        // half adds the remaining low nibble.
        if (state.first) {
          state.first = false;
          state.top = static_cast<uint8_t>(kAutoAdjustMultiplicand * low);
        } else {
          state.first = true;
          state.top = static_cast<uint8_t>(state.top + low);
        }
        continue;
      }

      store(flag ? static_cast<uint8_t>(high + 0x8) : high);
      if (low != 0xf) {
        store(low);
      }
    }
  }
  return DecodeResult::Ok;
}

DecodeResult decodeManual(HidReportSource &source, uint32_t datumCount,
                          uint8_t artifactValue, ManualState &state,
                          std::vector<uint8_t> &out,
                          const ProgressSink &onProgress) {
  out.clear();
  if (datumCount == 0) {
    return DecodeResult::Ok;
  }
  out.reserve(datumCount);

  PacketReassembler reassembler(source);
  uint32_t remaining = datumCount;
  const uint32_t maxPackets = packetBudget(datumCount);
  uint32_t packetsRead = 0;
  uint16_t expectedSeq = 0;
  uint8_t packet[kManualPacketLen];

  auto store = [&](uint8_t value) {
    // Matches store_datum_manual()'s own `if (datums)` guard: writes are
    // silently dropped once the record's datum count is exhausted, since a
    // single byte can decode into more datums than remain (see the
    // artifact/finger-out double-store case below).
    if (remaining == 0) {
      return;
    }
    out.push_back(value);
    remaining--;
  };

  while (remaining > 0) {
    if (packetsRead >= maxPackets) {
      return DecodeResult::PacketBudgetExceeded;
    }
    if (!reassembler.nextPacket(packet, kManualPacketLen)) {
      return DecodeResult::ReadError;
    }
    packetsRead++;
    if (onProgress && packetsRead % kProgressLogInterval == 0) {
      onProgress(packetsRead, remaining);
    }
    if (!checksumOk(packet, kManualDatumsEnd)) {
      return DecodeResult::ChecksumError;
    }
    const uint16_t gotSeq = static_cast<uint16_t>(
        packet[kManualSeqLsbIdx] + (packet[kManualSeqMsbIdx] << 7));
    if (expectedSeq != gotSeq) {
      return DecodeResult::SequenceError;
    }
    expectedSeq++;

    uint32_t flags = packet[kManualFlagsLsbIdx] +
                     (static_cast<uint32_t>(packet[kManualFlagsMsbIdx]) << 7);

    for (uint8_t i = kManualDatumsStart; i < kManualDatumsEnd && remaining > 0;
        i++) {
      const bool flag = (flags & 0x1) != 0;
      flags >>= 1;
      const uint8_t nibbles = packet[i];

      // "The first measurement B in [a 14-byte] datums command packet is
      // always an absolute value. Later nibbles in Bs are deltas..."
      if (state.byteIndexInGroup % kManualDatumBytesPerGroup == 0) {
        state.byteIndexInGroup++;
        const bool isArtifact = flag && nibbles == 0x7f;
        if (isArtifact) {
          store(artifactValue);
        } else {
          uint8_t absolute = nibbles;
          if (flag) { // 0x7f overflowed; always false for SpO2.
            absolute = static_cast<uint8_t>(absolute + 128);
          }
          state.datum = absolute;
          store(state.datum);
        }
        continue;
      }
      state.byteIndexInGroup++;

      if (flag && nibbles == 0x7f) {
        store(artifactValue);
        store(artifactValue);
        continue;
      }

      const uint8_t high = static_cast<uint8_t>(nibbles >> 4);
      if (high != 0) {
        state.datum = flag ? static_cast<uint8_t>(state.datum - high)
                           : static_cast<uint8_t>(state.datum + high);
      }
      store(state.datum);

      const uint8_t low = static_cast<uint8_t>(nibbles & 0x0f);
      if (low != 0) {
        if (low == 0xf) { // Finger is out.
          store(artifactValue);
          continue;
        }
        if (low & 0x8) {
          state.datum =
              static_cast<uint8_t>(state.datum - (low & 0x7));
        } else {
          state.datum = static_cast<uint8_t>(state.datum + low);
        }
      }
      store(state.datum);
    }
  }
  return DecodeResult::Ok;
}

} // namespace StoredRecordDecode

#ifdef ARDUINO

#include <ctime>

#include "Config.h"

namespace {

// Proleptic-Gregorian civil-calendar-to-epoch-seconds conversion (Howard
// Hinnant's days_from_civil algorithm), used instead of mktime()/localtime()
// deliberately: those depend on the host's configured timezone/DST (as
// pulseoxdl.c's own set_start_seconds() does, via the `timezone` global and
// tm_isdst), which makes no sense to replicate on an embedded target with no
// timezone concept of its own. Since RamCsvBuffer/FileCsvBuffer render epoch
// seconds back to calendar fields with gmtime_r(), what matters is that the
// round trip reproduces the exact %y%m%d%H%M%S the device reported, which
// this guarantees.
int64_t civilToEpochSeconds(int year, int month, int day, int hour,
                            int minute, int second) {
  const int y = month <= 2 ? year - 1 : year;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned mp = static_cast<unsigned>((month + 9) % 12);
  const unsigned doy = (153 * mp + 2) / 5 + static_cast<unsigned>(day) - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  const int64_t days =
      static_cast<int64_t>(era) * 146097LL + static_cast<int64_t>(doe) - 719468LL;
  return days * 86400LL + hour * 3600LL + minute * 60LL + second;
}

// Hex-dumps one HID report (or a command's meaningful prefix) to Serial, so
// every exchange with the PO-400 is visible while chasing a protocol
// mismatch — compare directly against a known-good capture (e.g. from
// pulseoxdl itself, run with its DEBUG_WRITE/DEBUG build flags) byte for
// byte.
void logHex(const char *label, const uint8_t *buf, size_t len) {
  Serial.printf("StoredRecordDownloader: %s (%u B):", label,
               static_cast<unsigned>(len));
  for (size_t i = 0; i < len; i++) {
    Serial.printf(" %02X", buf[i]);
  }
  Serial.println();
}

// For logging a decode failure's specific reason (distinguishing "the device
// stopped responding" from "the device kept responding but never converged,"
// which points at a datumCount/geometry mismatch rather than a transport
// problem — see PacketBudgetExceeded's comment in the header).
const char *decodeResultName(StoredRecordDecode::DecodeResult result) {
  switch (result) {
  case StoredRecordDecode::DecodeResult::Ok:
    return "Ok";
  case StoredRecordDecode::DecodeResult::ChecksumError:
    return "ChecksumError";
  case StoredRecordDecode::DecodeResult::SequenceError:
    return "SequenceError";
  case StoredRecordDecode::DecodeResult::ReadError:
    return "ReadError (device stopped responding)";
  case StoredRecordDecode::DecodeResult::PacketBudgetExceeded:
    return "PacketBudgetExceeded (device kept responding but the datum "
          "count never reached 0 — likely a metadata/geometry mismatch)";
  }
  return "Unknown";
}

// Adapts UsbHidOxHost's blocking readReport() to the pure decode namespace's
// HidReportSource interface.
class UsbHidReportSource : public StoredRecordDecode::HidReportSource {
public:
  explicit UsbHidReportSource(UsbHidOxHost &host) : host_(host) {}
  bool readReport(uint8_t report[64]) override {
    const bool ok = host_.readReport(report, 64, /*timeoutMs=*/3000);
    // First few raw datum-stream reports only, so a checksum/sequence
    // failure in decodeAuto/decodeManual (which can't log — no Serial in
    // the native-tested StoredRecordDecode namespace) is still diagnosable
    // from here without dumping thousands of lines for a full record.
    if (ok && loggedCount_ < kMaxLogged) {
      loggedCount_++;
      logHex("datum stream RX", report, 64);
    }
    return ok;
  }

private:
  static constexpr int kMaxLogged = 5;
  UsbHidOxHost &host_;
  int loggedCount_ = 0;
};

} // namespace

StoredRecordDownloader::StoredRecordDownloader(UsbHidOxHost &usbHost,
                                               ICsvBuffer &csvBuffer,
                                               ClockSync &clockSync)
    : usbHost_(usbHost), csvBuffer_(csvBuffer), clockSync_(clockSync) {}

void StoredRecordDownloader::begin() {
  preferences_.begin(Config::kPrefsNamespace, /*readOnly=*/false);
  testModeEnabled_ = preferences_.getBool(Config::kPrefsKeyTestMode,
                                         Config::kDefaultTestMode);
}

bool StoredRecordDownloader::testModeEnabled() const {
  return testModeEnabled_;
}

void StoredRecordDownloader::setTestMode(bool enabled) {
  testModeEnabled_ = enabled;
  preferences_.putBool(Config::kPrefsKeyTestMode, enabled);
}

bool StoredRecordDownloader::sendExchange(const uint8_t *writeData,
                                          size_t writeLen,
                                          uint8_t writeChecksumLen,
                                          uint8_t *readBuf, size_t readLen,
                                          uint8_t readChecksumLen,
                                          uint8_t expectedFirstByte) {
  uint8_t out[64] = {0};
  memcpy(out, writeData, writeLen);
  if (writeChecksumLen > 0) {
    uint8_t sum = 0;
    for (uint8_t i = 0; i + 1 < writeChecksumLen; i++) {
      sum = static_cast<uint8_t>(sum + out[i]);
    }
    out[writeChecksumLen - 1] = sum & 0x7f;
  }
  // writeLen (not sizeof(out)) — the rest is zero-padding, not interesting.
  logHex("TX", out, writeLen);
  if (!usbHost_.writeReport(out, sizeof(out))) {
    Serial.println(
        "StoredRecordDownloader: TX failed (writeReport returned false) — "
        "device likely detached or a USB OUT-transfer error.");
    return false;
  }

  uint8_t in[64] = {0};
  if (!usbHost_.readReport(in, sizeof(in), /*timeoutMs=*/3000)) {
    Serial.println(
        "StoredRecordDownloader: RX timed out after 3000ms waiting for a "
        "response.");
    return false;
  }
  logHex("RX", in, sizeof(in));
  if (expectedFirstByte != 0 && in[0] != expectedFirstByte) {
    Serial.printf(
        "StoredRecordDownloader: RX unexpected command byte 0x%02X "
        "(expected 0x%02X) — likely reading a stale/misaligned report.\n",
        in[0], expectedFirstByte);
    return false;
  }
  if (readChecksumLen > 0) {
    uint8_t sum = 0;
    for (uint8_t i = 0; i + 1 < readChecksumLen; i++) {
      sum = static_cast<uint8_t>(sum + in[i]);
    }
    if ((sum & 0x7f) != in[readChecksumLen - 1]) {
      Serial.printf(
          "StoredRecordDownloader: RX checksum mismatch — computed 0x%02X, "
          "device sent 0x%02X at byte %u.\n",
          sum & 0x7f, in[readChecksumLen - 1], readChecksumLen - 1);
      return false;
    }
  }
  if (readBuf && readLen > 0) {
    memcpy(readBuf, in, readLen);
  }
  return true;
}

bool StoredRecordDownloader::sendRequest(const uint8_t *writeData,
                                         size_t writeLen,
                                         uint8_t writeChecksumLen) {
  uint8_t out[64] = {0};
  memcpy(out, writeData, writeLen);
  if (writeChecksumLen > 0) {
    uint8_t sum = 0;
    for (uint8_t i = 0; i + 1 < writeChecksumLen; i++) {
      sum = static_cast<uint8_t>(sum + out[i]);
    }
    out[writeChecksumLen - 1] = sum & 0x7f;
  }
  logHex("TX (request, no response read here)", out, writeLen);
  if (!usbHost_.writeReport(out, sizeof(out))) {
    Serial.println(
        "StoredRecordDownloader: TX failed (writeReport returned false) — "
        "device likely detached or a USB OUT-transfer error.");
    return false;
  }
  return true;
}

void StoredRecordDownloader::appendDecodedRecord(
    int64_t startEpochSeconds, const std::vector<uint8_t> &spo2,
    const std::vector<uint8_t> &pr) {
  const size_t n = spo2.size() < pr.size() ? spo2.size() : pr.size();
  for (size_t i = 0; i < n; i++) {
    csvBuffer_.appendRow(startEpochSeconds + static_cast<int64_t>(i), spo2[i],
                        pr[i]);
  }
}

bool StoredRecordDownloader::downloadOneAutoRecord(
    uint8_t recordIndex, int64_t startEpochSeconds, uint32_t datumCount,
    StoredRecordDecode::AutoState &autoState) {
  UsbHidReportSource reportSource(usbHost_);

  uint8_t requestBuf[9];
  memcpy(requestBuf, Config::kCmdRequestAutoTemplate,
        sizeof(Config::kCmdRequestAutoTemplate));
  requestBuf[Config::kAutoRequestRecordIndex] = recordIndex;

  auto logProgress = [recordIndex](const char *measurement) {
    return [recordIndex, measurement](uint32_t packetsRead,
                                      uint32_t remaining) {
      Serial.printf("StoredRecordDownloader: Auto record #%u %s decode — "
                    "%u packets read, %u datums remaining...\n",
                    recordIndex, measurement, packetsRead, remaining);
    };
  };

  requestBuf[Config::kAutoRequestMeasurementIndex] = Config::kAutoMeasurementSpo2;
  if (!sendRequest(requestBuf, sizeof(requestBuf), sizeof(requestBuf))) {
    return false;
  }
  std::vector<uint8_t> spo2;
  StoredRecordDecode::DecodeResult result = StoredRecordDecode::decodeAuto(
      reportSource, datumCount, autoState, spo2, logProgress("SpO2"));
  if (result != StoredRecordDecode::DecodeResult::Ok) {
    Serial.printf("StoredRecordDownloader: Auto record #%u SpO2 decode "
                 "failed: %s.\n",
                 recordIndex, decodeResultName(result));
    return false;
  }

  requestBuf[Config::kAutoRequestMeasurementIndex] = Config::kAutoMeasurementPr;
  if (!sendRequest(requestBuf, sizeof(requestBuf), sizeof(requestBuf))) {
    return false;
  }
  std::vector<uint8_t> pr;
  result = StoredRecordDecode::decodeAuto(reportSource, datumCount, autoState,
                                          pr, logProgress("pulse-rate"));
  if (result != StoredRecordDecode::DecodeResult::Ok) {
    Serial.printf("StoredRecordDownloader: Auto record #%u pulse-rate "
                 "decode failed: %s.\n",
                 recordIndex, decodeResultName(result));
    return false;
  }

  appendDecodedRecord(startEpochSeconds, spo2, pr);
  return true;
}

bool StoredRecordDownloader::downloadOneManualRecord(
    int64_t startEpochSeconds, uint32_t datumCount,
    StoredRecordDecode::ManualState &manualState) {
  UsbHidReportSource reportSource(usbHost_);

  uint8_t requestBuf[5];
  memcpy(requestBuf, Config::kCmdRequestManualTemplate,
        sizeof(Config::kCmdRequestManualTemplate));

  auto logProgress = [](const char *measurement) {
    return [measurement](uint32_t packetsRead, uint32_t remaining) {
      Serial.printf("StoredRecordDownloader: Manual record %s decode — "
                    "%u packets read, %u datums remaining...\n",
                    measurement, packetsRead, remaining);
    };
  };

  requestBuf[0] = Config::kManualMeasurementSpo2;
  if (!sendRequest(requestBuf, sizeof(requestBuf), sizeof(requestBuf))) {
    return false;
  }
  std::vector<uint8_t> spo2;
  StoredRecordDecode::DecodeResult result = StoredRecordDecode::decodeManual(
      reportSource, datumCount, StoredRecordDecode::kArtifactSpo2,
      manualState, spo2, logProgress("SpO2"));
  if (result != StoredRecordDecode::DecodeResult::Ok) {
    Serial.printf(
        "StoredRecordDownloader: Manual record SpO2 decode failed: %s.\n",
        decodeResultName(result));
    return false;
  }

  // Switching measurement type resets the 14-byte group alignment — see
  // ManualState's comment and extract_datums()'s explicit
  // `mdatumbytesused = 0` in pulseoxdl.c.
  manualState.byteIndexInGroup = 0;
  requestBuf[0] = Config::kManualMeasurementPr;
  if (!sendRequest(requestBuf, sizeof(requestBuf), sizeof(requestBuf))) {
    return false;
  }
  std::vector<uint8_t> pr;
  result = StoredRecordDecode::decodeManual(
      reportSource, datumCount, StoredRecordDecode::kArtifactPr, manualState,
      pr, logProgress("pulse-rate"));
  if (result != StoredRecordDecode::DecodeResult::Ok) {
    Serial.printf("StoredRecordDownloader: Manual record pulse-rate decode "
                 "failed: %s.\n",
                 decodeResultName(result));
    return false;
  }

  appendDecodedRecord(startEpochSeconds, spo2, pr);
  return true;
}

void StoredRecordDownloader::drainStaleReports() {
  uint8_t discard[64];
  uint32_t drained = 0;
  constexpr uint32_t kMaxDrain = 64; // safety cap, not an expected count
  while (drained < kMaxDrain &&
        usbHost_.readReport(discard, sizeof(discard), /*timeoutMs=*/50)) {
    drained++;
  }
  if (drained > 0) {
    Serial.printf(
        "StoredRecordDownloader: drained %u stale report(s) queued before "
        "the handshake started.\n",
        drained);
  }
}

bool StoredRecordDownloader::performInitialHandshake() {
  drainStaleReports();

  Serial.println(
      "StoredRecordDownloader: initial handshake — stopping the device's "
      "default data stream...");
  Serial.println("StoredRecordDownloader: >> STOP_SENDING_DATA");
  if (!sendExchange(Config::kCmdStopSendingData,
                    sizeof(Config::kCmdStopSendingData),
                    /*writeChecksumLen=*/0, nullptr, 0,
                    /*readChecksumLen=*/2, /*expectedFirstByte=*/0xF0)) {
    Serial.println(
        "StoredRecordDownloader: STOP_SENDING_DATA failed.");
    return false;
  }
  Serial.println("StoredRecordDownloader: >> handshake status query 1/2");
  if (!sendExchange(Config::kCmdHandshakeUnknown0,
                    sizeof(Config::kCmdHandshakeUnknown0),
                    /*writeChecksumLen=*/0, nullptr, 0,
                    /*readChecksumLen=*/8, /*expectedFirstByte=*/0xF2)) {
    Serial.println(
        "StoredRecordDownloader: handshake status query 1/2 failed.");
    return false;
  }
  Serial.println("StoredRecordDownloader: >> handshake status query 2/2");
  if (!sendExchange(Config::kCmdHandshakeUnknown1,
                    sizeof(Config::kCmdHandshakeUnknown1),
                    /*writeChecksumLen=*/0, nullptr, 0,
                    /*readChecksumLen=*/2, /*expectedFirstByte=*/0xF0)) {
    Serial.println(
        "StoredRecordDownloader: handshake status query 2/2 failed.");
    return false;
  }

  if (clockSync_.hasBeenSet()) {
    // SYNCHRONIZE_DEVICE_DATE_AND_TIME: opcode byte + %y%m%d%H%M%S + 2
    // unclear-but-always-zero bytes + checksum. Only sent when we actually
    // have a trustworthy time (the phone may not have connected/sent
    // SET_TIME yet by the time the cable is plugged in) — pulseoxdl's own
    // comment calls this step "probably not strictly necessary," and writing
    // a bogus time to the device's onboard clock would be worse than
    // skipping it.
    const time_t t = static_cast<time_t>(clockSync_.now());
    struct tm tmVal;
    gmtime_r(&t, &tmVal);
    uint8_t syncTime[10] = {0};
    syncTime[0] = Config::kCmdSyncTimeOpcode;
    syncTime[1] = static_cast<uint8_t>(tmVal.tm_year - 100);
    syncTime[2] = static_cast<uint8_t>(tmVal.tm_mon + 1);
    syncTime[3] = static_cast<uint8_t>(tmVal.tm_mday);
    syncTime[4] = static_cast<uint8_t>(tmVal.tm_hour);
    syncTime[5] = static_cast<uint8_t>(tmVal.tm_min);
    syncTime[6] = static_cast<uint8_t>(tmVal.tm_sec);
    Serial.println(
        "StoredRecordDownloader: >> SYNCHRONIZE_DEVICE_DATE_AND_TIME");
    if (!sendExchange(syncTime, sizeof(syncTime), sizeof(syncTime), nullptr,
                      0, /*readChecksumLen=*/3, /*expectedFirstByte=*/0xF3)) {
      Serial.println(
          "StoredRecordDownloader: device clock sync failed (continuing "
          "anyway).");
    }
  } else {
    Serial.println(
        "StoredRecordDownloader: skipping device clock sync — phone "
        "hasn't sent SET_TIME yet.");
  }

  Serial.println("StoredRecordDownloader: >> USER_NAME");
  if (!sendExchange(Config::kCmdUserName, sizeof(Config::kCmdUserName),
                    /*writeChecksumLen=*/0, nullptr, 0,
                    /*readChecksumLen=*/10, /*expectedFirstByte=*/0xFE)) {
    Serial.println("StoredRecordDownloader: USER_NAME query failed.");
    return false;
  }
  Serial.println("StoredRecordDownloader: >> MODEL_NAME");
  if (!sendExchange(Config::kCmdModelName, sizeof(Config::kCmdModelName),
                    /*writeChecksumLen=*/0, nullptr, 0,
                    /*readChecksumLen=*/10, /*expectedFirstByte=*/0xF1)) {
    Serial.println("StoredRecordDownloader: MODEL_NAME query failed.");
    return false;
  }
  Serial.println(
      "StoredRecordDownloader: initial handshake complete.");
  return true;
}

bool StoredRecordDownloader::downloadAndMaybeDelete() {
  if (!performInitialHandshake()) {
    Serial.println(
        "StoredRecordDownloader: initial handshake failed; device may "
        "have been unplugged.");
    return false;
  }

  Serial.println(
      "StoredRecordDownloader: checking PO-400 for stored records...");
  Serial.println("StoredRecordDownloader: >> STORED_PRESENT");
  uint8_t presentResp[8] = {0};
  if (!sendExchange(Config::kCmdStoredPresent,
                    sizeof(Config::kCmdStoredPresent), /*writeChecksumLen=*/0,
                    presentResp, sizeof(presentResp),
                    /*readChecksumLen=*/8, /*expectedFirstByte=*/0xEF)) {
    Serial.println(
        "StoredRecordDownloader: failed to query stored-record presence.");
    return false;
  }
  const bool present = presentResp[2] != 0;
  if (!present) {
    Serial.println("StoredRecordDownloader: no stored records present.");
    return true; // Nothing to download; not an error.
  }
  const bool isAuto = presentResp[3] != 0;
  Serial.printf("StoredRecordDownloader: stored %s record(s) present — "
               "downloading (test mode %s).\n",
               isAuto ? "Auto" : "Manual",
               testModeEnabled_ ? "ON, device data kept" : "OFF, will delete");

  if (isAuto) {
    // Generous but hard bound on how many records this loop will pull
    // before the device ever reports "last record" — a real PO-400's stored
    // buffer realistically holds far fewer sessions than this. Without it,
    // a metadata-format mismatch (e.g. the "isLast" byte at a different
    // offset than assumed) would keep this loop running forever rather than
    // failing loudly, since each individual record download can succeed on
    // its own terms and never trips a transport-level timeout.
    constexpr uint32_t kMaxAutoRecordsPerSession = 64;
    StoredRecordDecode::AutoState autoState;
    bool isLast = false;
    uint32_t recordsDownloaded = 0;
    while (!isLast) {
      if (recordsDownloaded >= kMaxAutoRecordsPerSession) {
        Serial.printf(
            "StoredRecordDownloader: aborting — downloaded %u Auto "
            "records without the device ever reporting 'last record'; "
            "likely a metadata-format mismatch.\n",
            recordsDownloaded);
        return false;
      }
      uint8_t meta[21] = {0};
      Serial.println(
          "StoredRecordDownloader: >> GET_RECORD_METADATA_AUTO");
      if (!sendExchange(Config::kCmdGetRecordMetadataAuto,
                        sizeof(Config::kCmdGetRecordMetadataAuto),
                        /*writeChecksumLen=*/0, meta, sizeof(meta),
                        /*readChecksumLen=*/21, /*expectedFirstByte=*/0xEC)) {
        Serial.println(
            "StoredRecordDownloader: failed to read Auto record metadata.");
        return false;
      }
      isLast = meta[1] != 0;
      const uint8_t recordIndex = meta[3];
      const int year = 2000 + meta[4];
      const int month = meta[5];
      const int day = meta[6];
      const int hour = meta[7];
      const int minute = meta[8];
      const int second = meta[9];
      const uint32_t datumCount =
          static_cast<uint32_t>(meta[10]) +
          (static_cast<uint32_t>(meta[11]) << 7) +
          (static_cast<uint32_t>(meta[12]) << 14);
      const int64_t startEpoch =
          civilToEpochSeconds(year, month, day, hour, minute, second);

      Serial.printf(
          "StoredRecordDownloader: downloading Auto record #%u — "
          "%04d-%02d-%02d %02d:%02d:%02d, %u datums...\n",
          recordIndex, year, month, day, hour, minute, second, datumCount);
      if (!downloadOneAutoRecord(recordIndex, startEpoch, datumCount,
                                 autoState)) {
        Serial.printf(
            "StoredRecordDownloader: failed downloading Auto record #%u.\n",
            recordIndex);
        return false;
      }
      recordsDownloaded++;
    }
    Serial.printf(
        "StoredRecordDownloader: all %u Auto record(s) downloaded.\n",
        recordsDownloaded);

    if (!testModeEnabled_) {
      Serial.println(
          "StoredRecordDownloader: deleting Auto records from device.");
      sendExchange(Config::kCmdDeleteRecordsAuto,
                  sizeof(Config::kCmdDeleteRecordsAuto),
                  /*writeChecksumLen=*/0, nullptr, 0,
                  /*readChecksumLen=*/0);
    }
  } else {
    uint8_t meta[14] = {0};
    Serial.println("StoredRecordDownloader: >> GET_RECORD_METADATA_MANUAL");
    if (!sendExchange(Config::kCmdGetRecordMetadataManual,
                      sizeof(Config::kCmdGetRecordMetadataManual),
                      /*writeChecksumLen=*/0, meta, sizeof(meta),
                      /*readChecksumLen=*/14, /*expectedFirstByte=*/0xD0)) {
      Serial.println(
          "StoredRecordDownloader: failed to read Manual record metadata.");
      return false;
    }
    const int year = 2000 + meta[2];
    const int month = meta[3];
    const int day = meta[4];
    const int hour = meta[5];
    const int minute = meta[6];
    const int second = meta[7];
    const uint32_t datumCount = static_cast<uint32_t>(meta[10]) +
                                (static_cast<uint32_t>(meta[11]) << 7) +
                                (static_cast<uint32_t>(meta[12]) << 14);
    const int64_t startEpoch =
        civilToEpochSeconds(year, month, day, hour, minute, second);

    Serial.printf(
        "StoredRecordDownloader: downloading Manual record — "
        "%04d-%02d-%02d %02d:%02d:%02d, %u datums...\n",
        year, month, day, hour, minute, second, datumCount);
    StoredRecordDecode::ManualState manualState;
    if (!downloadOneManualRecord(startEpoch, datumCount, manualState)) {
      Serial.println(
          "StoredRecordDownloader: failed downloading Manual record.");
      return false;
    }
    Serial.println("StoredRecordDownloader: Manual record downloaded.");

    if (!testModeEnabled_) {
      Serial.println(
          "StoredRecordDownloader: deleting Manual record from device.");
      sendExchange(Config::kCmdDeleteRecordManual0,
                  sizeof(Config::kCmdDeleteRecordManual0),
                  /*writeChecksumLen=*/0, nullptr, 0,
                  /*readChecksumLen=*/0);
      sendExchange(Config::kCmdDeleteRecordManual1,
                  sizeof(Config::kCmdDeleteRecordManual1),
                  /*writeChecksumLen=*/0, nullptr, 0,
                  /*readChecksumLen=*/0);
    }
  }

  return true;
}

#endif // ARDUINO
