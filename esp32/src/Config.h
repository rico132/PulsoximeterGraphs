// Config.h — central constants for the PulsoxRelay ESP32 firmware.
//
// The BLE UUIDs, opcodes and buffer-cap conventions here MUST mirror
// /home/riccardo/medi/PulsoximeterGraphs/PROTOCOL.md exactly — that file is the
// single source of truth shared with the independently-built Android app.
// If the protocol ever changes, update PROTOCOL.md first, then this file.
#pragma once

#include <cstddef>
#include <cstdint>

namespace Config {

// ---------------------------------------------------------------------------
// USB HID (PO-400 / Contec CMS50E family)
// ---------------------------------------------------------------------------
constexpr uint16_t kUsbVendorId = 0x28E9;
constexpr uint16_t kUsbProductId = 0x028A;
constexpr size_t kHidReportSize = 64; // unnumbered, both directions

// Initial per-attach handshake, sent once before any stored-record exchange
// below. Mirrors the manufacturer software's own sequence, per pulseoxdl's
// exchange.h: "the sequence of exchanges that manufacturer's software does
// with the device every time on initial communication." The PO-400
// otherwise defaults to spontaneously streaming its own live-reading reports
// over the same interrupt endpoint used for these command/response
// exchanges — STOP_SENDING_DATA is what silences that, which is what
// actually makes STORED_PRESENT/GET_RECORD_METADATA_* below reliable instead
// of racing unsolicited live packets (see StoredRecordDownloader.cpp).
constexpr uint8_t kCmdStopSendingData[18] = {
    0x7D, 0x81, 0xA7, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    0x7D, 0x81, 0xA2, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80};
// Unidentified in pulseoxdl's own comments ("could be whether PI-capable,
// etc.") but sent unconditionally by the manufacturer software with no
// documented side effect — kept for parity since we can't verify their
// necessity against real hardware ourselves.
constexpr uint8_t kCmdHandshakeUnknown0[2] = {0x82, 0x02};
constexpr uint8_t kCmdHandshakeUnknown1[2] = {0x80, 0x00};
// SYNCHRONIZE_DEVICE_DATE_AND_TIME's opcode byte; bytes 1-6 (%y%m%d%H%M%S)
// and the checksum are filled in at send time from ClockSync, only when a
// trustworthy time is available — see StoredRecordDownloader.cpp.
constexpr uint8_t kCmdSyncTimeOpcode = 0x83;
constexpr uint8_t kCmdUserName[3] = {0x8E, 0x03, 0x11};
constexpr uint8_t kCmdModelName[2] = {0x81, 0x01};

// Stored-record (Auto/Manual) exchange commands, from pulseoxdl's exchange.h.
constexpr uint8_t kCmdStoredPresent[2] = {0x9F, 0x1F};
constexpr uint8_t kCmdGetRecordMetadataAuto[3] = {0x9C, 0x01, 0x1D};
constexpr uint8_t kCmdGetRecordMetadataManual[3] = {0xA0, 0x00, 0x20};
constexpr uint8_t kCmdDeleteRecordsAuto[8] = {0x9D, 0x7F, 0x7F, 0x7F, 0x7F,
                                              0x00, 0x00, 0x19};
constexpr uint8_t kCmdDeleteRecordManual0[3] = {0xA1, 0x00, 0x21};
constexpr uint8_t kCmdDeleteRecordManual1[3] = {0xA1, 0x01, 0x22};

// Auto/Manual "requestauto"/"requestmanual" datum-download command templates
// (byte layout only — the measurement-selector byte and, for Auto, the record
// index byte, are patched in at send time by StoredRecordDownloader).
constexpr uint8_t kCmdRequestAutoTemplate[9] = {0x9D, 0x04, 0x01, 0x01, 0x01,
                                                0x00, 0x00, 0x00, 0x24};
constexpr uint8_t kCmdRequestManualTemplate[5] = {0xA3, 0x00, 0x00, 0x00, 0x23};
constexpr uint8_t kManualMeasurementSpo2 = 0xA3; // requestmanual.data[0] default
constexpr uint8_t kManualMeasurementPr = 0xA2;   // REQUEST_MANUAL_PR
constexpr uint8_t kAutoMeasurementSpo2 = 0x01;
constexpr uint8_t kAutoMeasurementPr = 0x02;
constexpr uint8_t kAutoRequestMeasurementIndex = 2; // requestauto.data[2]
constexpr uint8_t kAutoRequestRecordIndex = 4;      // requestauto.data[4]

// ---------------------------------------------------------------------------
// BLE GATT service — PROTOCOL.md §"BLE GATT service"
// ---------------------------------------------------------------------------
constexpr const char *kDeviceName = "PulsoxRelay";
constexpr const char *kServiceUuid = "6f2a1000-2f5b-4a9c-91c4-0d8f6b1c9a01";
constexpr const char *kControlCharUuid = "6f2a1001-2f5b-4a9c-91c4-0d8f6b1c9a01";
constexpr const char *kDataCharUuid = "6f2a1002-2f5b-4a9c-91c4-0d8f6b1c9a01";
constexpr const char *kStatusCharUuid = "6f2a1003-2f5b-4a9c-91c4-0d8f6b1c9a01";
// Raw firmware-image bytes, phone -> ESP32, chunked the same way the Data characteristic
// chunks CSV bytes ESP32 -> phone (each write <= negotiatedMtu - 3) — see BleFirmwareUpdater
// and PROTOCOL.md §"BLE firmware update (OTA over BLE)".
constexpr const char *kFirmwareCharUuid = "6f2a1004-2f5b-4a9c-91c4-0d8f6b1c9a01";

// Control opcodes (1 byte, optional payload) — PROTOCOL.md §"Control opcodes".
enum ControlOpcode : uint8_t {
  kOpRequestData = 0x01,
  kOpSetTime = 0x02,
  kOpClearBuffer = 0x03,
  kOpSetTestMode = 0x04,
  // 0x05 (SET_WIFI_CREDENTIALS) and 0x06 (ENTER_OTA_MODE) used to drive the WiFi/ArduinoOTA
  // update path (WiFiManager captive portal + ArduinoOTA, see the removed OtaManager) — retired
  // now that BLE OTA below is the only firmware-update path, but deliberately left unassigned
  // rather than reused, so old PROTOCOL.md revisions/logs referencing them aren't misleading.
  // Payload: [size:u32 LE][expectedMd5Hex:32 ASCII bytes]. Begins receiving a new firmware
  // image into the *inactive* OTA partition — see BleFirmwareUpdater::begin(). Refused (see
  // its own status result) while a USB stored-record download is in progress, or while an
  // update is already underway.
  kOpStartFirmwareUpdate = 0x07,
  // No payload. Sent once the phone has written exactly `size` bytes to the Firmware
  // characteristic. Verifies size + MD5 and, ONLY on success, flips the boot partition to the
  // newly-written image (Update::end()'s own behavior — see BleFirmwareUpdater::finish()) and
  // reboots. On failure, the currently-running firmware and its boot partition are completely
  // untouched; nothing reboots.
  kOpFinishFirmwareUpdate = 0x08,
  // No payload. Discards whatever has been written so far and frees the in-progress OTA
  // handle, without touching the boot partition — e.g. the phone gave up mid-transfer.
  kOpAbortFirmwareUpdate = 0x09,
  // No payload. Deletes every BLE bond this ESP32 currently holds (NimBLEDevice::
  // deleteAllBonds()) — including the one for the very phone sending this opcode — and also
  // regenerates the pairing PIN (same generation path as the serial-only `blepin` command),
  // then disconnects. Any phone, this one included, must re-pair from scratch afterward using
  // whichever new PIN this printed to the serial log. The PIN rotation is what makes this an
  // actual lockout rather than just a forced re-pair — clearing bonds alone would leave anyone
  // who already knows the (unchanged) PIN able to just pair straight back. Reachable over BLE
  // (unlike a bare PIN regeneration on its own, which stays serial-only via `blepin`) since this
  // can only ever *reduce* what a connection is trusted to do, never grant new trust — the write
  // to get here already required passing the exact same encrypted+authenticated gate every
  // other Control opcode does, and neither clearing bonds nor rotating the PIN can be used to
  // bypass or escalate anything.
  kOpUnpairAllDevices = 0x0A,
};

// End-of-transfer marker on the Data characteristic: exactly one notification
// containing a single 0x00 byte (CSV text never contains 0x00).
constexpr uint8_t kEndOfTransferByte = 0x00;

// Real hardware logs showed the *last* notification of an otherwise fully-sent transfer
// occasionally never reach the phone -- app-side inactivity timeout, not an ESP32-side error --
// consistent with GATT notify()'s fundamental limitation: it reports whether the packet was
// queued for transmission, never whether the peer actually received it, and a burst this large
// ending abruptly is exactly where a queue-drain race is most likely to eat the very last packet
// specifically. requestDataDump() now pauses briefly before sending the terminator (to let
// whatever's still queued from the preceding burst actually drain first) and resends it
// kTerminatorSendCount times rather than once: a duplicate is harmless (BleGattClient.kt's
// onDataChunk ignores anything arriving once a transfer's already been fully handled) and this
// directly covers the one packet a 30-second app-side timeout+retry would otherwise be the only
// recourse for.
constexpr int kTerminatorSendCount = 3;
constexpr uint32_t kTerminatorSendDelayMs = 30;

// MTU negotiation: request 503 (500-byte chunks; NimBLE's own ceiling is
// BLE_ATT_MTU_MAX == 527, see ble_att.h), must also work at the default
// un-negotiated 23. BLE MTU exchange settles on the *smaller* of what each
// side proposes, so this value is what actually caps the negotiated MTU on
// a real device even if the phone asks for more -- it must be raised here,
// not just in the Android app's BleConstants.kt, for hardware syncs to see
// any benefit from a higher chunk size (PROTOCOL.md's shared contract).
constexpr uint16_t kPreferredMtu = 503;
constexpr uint16_t kMinMtu = 23;

// ---------------------------------------------------------------------------
// Buffering — plan §"Buffering"
// ---------------------------------------------------------------------------
// No FIFO rotation (simplest correct MVP behavior): once full, recording
// stops until CLEAR_BUFFER. Capacity itself is no longer a fixed row count —
// a hardcoded cap (previously 80,000 rows / 3.2MB, tuned to leave headroom
// under the ~3.87MB littlefs partition) silently discarded real, otherwise-
// undownloaded measurement data once hit, even with hundreds of KB of
// actually-free space still sitting unused on both the RAM and File buffers.
// RamCsvBuffer and FileCsvBuffer each size themselves dynamically instead,
// from however much space is actually free (PSRAM largest-free-block, or
// the littlefs partition's free bytes) minus a safety margin — see their
// own begin()/refreshRemainingCapacity() comments — so the true limit is
// "how much room is actually left on this board," not a number tuned
// against one previously-seen device's history.
constexpr uint32_t kApproxBytesPerRow = 30; // typical row size, for the plan's back-of-envelope budget
// Worst-case row: "YYYY-MM-DD, HH:MM:SS, 100, 255\r\n" == 32 bytes; round up
// for headroom so a single row is never assumed to fit in less than this.
constexpr uint32_t kMaxCsvRowLength = 40;
// Reserved, not handed to RamCsvBuffer's PSRAM arena, so other subsystems
// that might still allocate PSRAM later in setup() (NimBLE, ...) — all of
// which init *after* RamCsvBuffer::begin() in main.cpp — aren't starved by
// this buffer having already claimed nearly all of it.
constexpr size_t kPsramArenaSafetyMarginBytes = 512 * 1024;
// Reserved, not handed to FileCsvBuffer's row budget, for LittleFS's own
// metadata/wear-leveling overhead and any other files sharing the same
// littlefs partition.
constexpr size_t kFilesystemFreeSpaceSafetyMarginBytes = 64 * 1024;

// ---------------------------------------------------------------------------
// USB host power switch (ESP32-S3-USB-OTG board's MIC2005A VBUS switch)
// ---------------------------------------------------------------------------
constexpr int kUsbHostVbusEnableGpio = 17; // IDEV_LIMIT_EN

// ---------------------------------------------------------------------------
// NVS / Preferences keys
// ---------------------------------------------------------------------------
constexpr const char *kPrefsNamespace = "pulsoxrelay";
constexpr const char *kPrefsKeyTestMode = "test_mode";
constexpr bool kDefaultTestMode = true; // default ON — never destroy real data

// Tracks which stored records have already been successfully appended to
// the CSV buffer, keyed by (recordIndex, startEpoch) for Auto and by
// startEpoch alone for Manual (only one Manual "slot" exists on the device
// at a time) — see StoredRecordDownloader's isAutoRecordCommitted()/
// isManualRecordCommitted() comments. Persisted (not just in-RAM) because a
// real replug, or an ESP32-triggered resync (see BleGattServer::
// requestDataDump()), forces the entire enumerate+download loop to restart
// from scratch on the next attach — all within the *same* power-on session
// — without this, every such retry re-decodes AND re-appends every record
// that had already succeeded, burning through the buffer's capacity with
// duplicates of the same data instead of just picking up where it left off.
// Deliberately NOT meant to survive an actual reboot, though: health data
// sitting durably in flash across a power cycle isn't something this
// firmware wants, now that a REQUEST_DATA finding nothing buffered yet will
// itself trigger a fresh re-download rather than silently returning
// nothing — StoredRecordDownloader::begin() resets both keys on every boot,
// in lockstep with the CSV buffer itself also starting empty every boot
// (see FileCsvBuffer::begin()'s comment); a mid-download crash/watchdog
// reboot now simply means the next attach re-downloads everything from
// scratch, same as any other post-reboot attach, rather than resuming.
// Also reset (both keys) whenever the phone's CLEAR_BUFFER wipes the buffer
// itself — see BleGattServer's handler — since a previously-committed
// record's rows no longer exist to skip re-downloading.
constexpr const char *kPrefsKeyCommittedAutoEpochs = "auto_committed";
constexpr const char *kPrefsKeyCommittedManualEpoch = "manual_committed";

// See OtaRollbackGuard's own doc. Counts consecutive boots of a not-yet-confirmed
// (ESP_OTA_IMG_PENDING_VERIFY) OTA image that never reached confirmHealthy() — reset to 0 on
// either a successful confirm or a rollback. Persisted (not just in-RAM) specifically because
// the failure mode this guards against is a firmware that crashes/hangs *during* setup(), which
// means each failed attempt is its own fresh boot with no surviving in-RAM state — only NVS
// survives across that.
constexpr const char *kPrefsKeyOtaBootAttempts = "ota_boot_try";

// BLE pairing PIN — see BleGattServer::begin()'s NimBLEDevice::setSecurityAuth()
// call and the Control/Data/Status characteristics' *_ENC/*_AUTHEN
// properties: without pairing, literally anyone within BLE range could
// connect to a "PulsoxRelay" and read someone's SpO2/pulse history — health
// data — with zero access control. Deliberately never a fixed literal baked
// in here: if this repo is ever shared, a hardcoded PIN committed to source
// stops being a secret at all. Instead BleGattServer generates a random one
// on first boot (or after an NVS wipe), persists it under this key, and
// prints it to Serial every boot so it stays retrievable. Deliberately
// never settable via any BLE opcode, for the same reason as everything in
// the BLE firmware update section below: a not-yet-paired attacker could
// simply set their own known PIN — see BleGattServer::regeneratePairingPasskey(),
// reachable only via main.cpp's bare `blepin` serial debug signal (no
// argument: a replacement PIN is always randomly generated, never
// operator-chosen, same as first boot).
constexpr const char *kPrefsKeyBlePasskey = "ble_passkey";

// ---------------------------------------------------------------------------
// BLE firmware update (OTA over BLE) — PROTOCOL.md §"BLE firmware update"
// ---------------------------------------------------------------------------
// Overridable at build time (see platformio.ini's `-D FIRMWARE_VERSION=...`), so a CI-built
// release binary's compiled-in version always matches the git tag it's published under — the
// Android app compares this (read from the Status characteristic) against the latest GitHub
// release tag to decide whether an update is actually available. "dev" here is only ever what
// a local, non-release build reports; it deliberately never matches a real release tag, so a
// locally-flashed dev build always reads as "outdated" rather than silently comparing equal to
// whatever the latest tag happens to be.
#ifndef FIRMWARE_VERSION
#define FIRMWARE_VERSION "dev"
#endif
constexpr const char *kFirmwareVersion = FIRMWARE_VERSION;

// Status characteristic (see BleGattServer) notification tags — the first byte of any
// notification on that characteristic says which of these it is; everything after is that
// message's own payload. Distinct from the Control opcodes above (those flow phone -> ESP32;
// these flow ESP32 -> phone) despite FIRMWARE_UPDATE_RESULT sharing FINISH_FIRMWARE_UPDATE's
// opcode value — the two are never ambiguous since they're never read from the same byte
// stream.
enum StatusTag : uint8_t {
  // Payload: kFirmwareVersion's bytes, ASCII, NOT null-terminated. Sent once on every read of
  // the Status characteristic (BleGattServer has no other use for it yet) — see
  // BleFirmwareUpdater's own doc for why the phone needs this at all.
  kStatusFirmwareVersion = 0x01,
  // Payload: 1 byte, 0x01 success / 0x00 failure, then (failure only) 1 more byte: either
  // Update.h's own UPDATE_ERROR_* code (see Update.h) if the failure happened during
  // writing/finalizing, or the sentinel 0xFF for a failure BleGattServer itself rejected the
  // attempt for before ever touching Update (e.g. a USB download was in progress, or one
  // firmware update was already under way) — see BleGattServer::handleStartFirmwareUpdate().
  // Notified exactly once per attempt: either once kOpFinishFirmwareUpdate concludes, or
  // immediately if kOpStartFirmwareUpdate itself was rejected.
  kStatusFirmwareUpdateResult = 0x08,
};

} // namespace Config
