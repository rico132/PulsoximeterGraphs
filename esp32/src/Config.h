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

// Control opcodes (1 byte, optional payload) — PROTOCOL.md §"Control opcodes".
enum ControlOpcode : uint8_t {
  kOpRequestData = 0x01,
  kOpSetTime = 0x02,
  kOpClearBuffer = 0x03,
  kOpSetTestMode = 0x04,
  kOpSetWifiCredentials = 0x05,
  kOpEnterOtaMode = 0x06,
};

// End-of-transfer marker on the Data characteristic: exactly one notification
// containing a single 0x00 byte (CSV text never contains 0x00).
constexpr uint8_t kEndOfTransferByte = 0x00;

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
// ~30 bytes/CSV-row; hard cap at ~12h buffered (43,200 rows) before recording
// stops until CLEAR_BUFFER. No FIFO rotation (simplest correct MVP behavior).
constexpr uint32_t kMaxBufferedRows = 43200;
constexpr uint32_t kApproxBytesPerRow = 30; // typical row size, for the plan's back-of-envelope budget
// Worst-case row: "YYYY-MM-DD, HH:MM:SS, 100, 255\r\n" == 32 bytes; round up
// for headroom so the RAM/File buffer arena is sized safely, not just typically.
constexpr uint32_t kMaxCsvRowLength = 40;
constexpr uint32_t kMaxBufferedBytes = kMaxBufferedRows * kMaxCsvRowLength;

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

// ---------------------------------------------------------------------------
// OTA / WiFi
// ---------------------------------------------------------------------------
constexpr const char *kOtaSetupApName = "PulsoxRelay-Setup";
constexpr const char *kOtaHostname = "pulsoxrelay";
constexpr uint32_t kOtaIdleTimeoutMs = 10UL * 60UL * 1000UL; // 10 minutes
constexpr const char *kPrefsKeyOtaPassword = "ota_pass";
// No default password baked in: OtaManager treats an empty/unset stored
// password as "OTA password not yet provisioned" and refuses ArduinoOTA
// begin() in that state. Per the plan's licensing/security note, the OTA
// password must only ever be set via a physically-attached serial/USB debug
// command, never over BLE, so a compromised phone link alone can't push
// firmware.

} // namespace Config
