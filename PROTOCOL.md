# Shared Protocol Contract — Android app ↔ ESP32 firmware

This is the single source of truth for the wire contract between `android/` and `esp32/`.
Both codebases' constants (`android/app/src/main/java/.../data/ble/BleConstants.kt` and
`esp32/src/Config.h`) must mirror this file exactly. If the contract ever changes, update
this file first.

## CSV format

Header line: `DATE,TIME,SPO2,PULSE`

Row format: `YYYY-MM-DD, HH:MM:SS, <spo2>, <pulse>\r\n`

- Comma-space separators.
- CRLF line endings (parsers on both sides must also tolerate bare `\n`).
- One row per second of measurement.
- No timezone information is carried — both sides treat timestamps as local time of
  whichever phone last sent `SET_TIME` (see below). This is a known limitation.

Example (from the source device, confirmed against the user's sample):

```
DATE,TIME,SPO2,PULSE
2026-08-12, 19:15:38, 96, 75
```

## BLE GATT service

ESP32 = peripheral/server. Phone = central/client. Advertised device name: `PulsoxRelay`.

| UUID | Characteristic | Properties | Purpose |
|---|---|---|---|
| `6f2a1000-2f5b-4a9c-91c4-0d8f6b1c9a01` | Service | — | scan filter target |
| `6f2a1001-2f5b-4a9c-91c4-0d8f6b1c9a01` | Control | write-with-response | opcodes below |
| `6f2a1002-2f5b-4a9c-91c4-0d8f6b1c9a01` | Data | notify | chunked CSV bytes |
| `6f2a1003-2f5b-4a9c-91c4-0d8f6b1c9a01` | Status (stretch, not MVP) | read/notify | buffered-row count |

### Control opcodes (1 byte, optional payload)

| Opcode | Name | Payload | Meaning |
|---|---|---|---|
| `0x01` | `REQUEST_DATA` | none | Ask the ESP32 to stream all currently-buffered CSV rows over the Data characteristic. |
| `0x02` | `SET_TIME` | 8 bytes, little-endian Unix epoch seconds | Phone pushes its current clock to the ESP32 (which has no RTC/NTP). Sent on every connection. |
| `0x03` | `CLEAR_BUFFER` | none | Phone confirms it has durably stored the data it just received; ESP32 may discard its buffered copy. Must only be sent **after** a successful local insert. |
| `0x04` | `SET_TEST_MODE` | 1 byte, `0x00` or `0x01` | When `0x01` (on), the ESP32 never deletes downloaded stored records from the PO-400. Persisted in NVS. **Defaults to `0x01` (on/non-destructive)** until explicitly turned off. |
| `0x05` | `SET_WIFI_CREDENTIALS` | `[ssidLen:u8][ssid bytes][passLen:u8][pass bytes]` | Configure WiFi STA credentials for OTA, stored via WiFiManager's NVS storage. |
| `0x06` | `ENTER_OTA_MODE` | none | Ask the ESP32 to bring up WiFi (using stored credentials, or a captive portal if none stored) and start `ArduinoOTA`, so a `pio run -t upload --upload-port <ip>` can flash new firmware. WiFi auto-tears-down after an idle timeout. |

### Data characteristic (notify)

- Raw CSV ASCII bytes (including `\r\n`), chunked to `negotiatedMtu - 3` bytes per notification
  (request MTU 503, i.e. up to a 500-byte chunk; must also work correctly at the default
  un-negotiated MTU of 23, and at any smaller chunk size a sender chooses to use — chunk size
  is not fixed, notifications are just concatenated in arrival order until the terminator).
- BLE notifications on one characteristic are delivered in order on a connected link, so no
  sequence numbers are used.
- End-of-transfer marker: one final notification containing **exactly one `0x00` byte**
  (CSV text can never contain a `0x00` byte, so this is unambiguous).

### Recommended sync sequence (phone side)

1. Connect, discover services, request MTU 503.
2. Write `SET_TIME` (always, every connection).
3. Write `REQUEST_DATA`.
4. Reassemble Data notifications until the `0x00` terminator; parse the CSV blob.
5. Only after the local insert succeeds, write `CLEAR_BUFFER`.

This ordering is what makes the protocol crash-safe: if the phone dies mid-insert, the
ESP32 still has the data next time.

## Threshold config defaults

Bundled as the Android app's `assets/default_thresholds.json`; not used by the firmware.

```json
{
  "spo2Orange": 95,
  "spo2Red": 90,
  "pulseLowOrange": 50,
  "pulseLowRed": 45,
  "pulseHighOrange": 90,
  "pulseHighRed": 100
}
```

Ordering invariant enforced by validation on both read and write:
`pulseLowRed < pulseLowOrange < pulseHighOrange < pulseHighRed` and `spo2Red < spo2Orange`.

## PO-400 USB HID protocol (device side, for firmware reference)

Confirmed by reading the GPLv3 reference project `pulseoxdl` (Contec CMS50E family).
USB VID:PID `28E9:028A`. 64-byte HID reports both directions, unnumbered (no report-ID
prefix byte on the real USB wire).

**Live streaming**:
- Start: OUTPUT reports `0x9B 0x00 0x1B` then `0x9B 0x01 0x1C` (64 bytes, zero-padded).
  Confirm an `0xEB`-prefixed ack INPUT report.
- Keepalive: OUTPUT report `0x9A 0x1A` roughly every 5 seconds, or the device stops streaming.
- Stop: OUTPUT report `0x9B 0x7F 0x1A`.
- Each INPUT report packs concatenated sub-commands (never crossing a report boundary),
  each starting with sync byte `0xEB`:
  - Waveform (`buf[1]==0x00`), 6 bytes — discarded, but must be walked to keep the scanner
    in sync.
  - Measurement (`buf[1]==0x01`), 8 bytes — `pr = (buf[2]&0x02) ? buf[3]+0x80 : buf[3]`;
    `spo2 = buf[4]` (`0x7F` = finger-out, skip); checksum = sum of preceding bytes & 0x7F,
    must equal the last byte (mismatch → resync by advancing 1 byte, not `length`).
  - Loop ends normally on hitting non-`0xEB` padding.

**Stored records** (Auto/Manual mode, device's own onboard memory):
- `STORED_PRESENT`: write `0x9f, 0x1f` → response indicates presence + Auto-vs-Manual mode.
- `GET_RECORD_METADATA_AUTO`: `0x9c, 0x01, 0x1d`. `GET_RECORD_METADATA_MANUAL`: `0xa0, 0x00, 0x20`.
  Gives start time (`%y%m%d%H%M%S`) and datum count (3×7-bit bytes, LSB order).
- Datum download + decode is bit-packed (2 datums/byte for Auto with carry-flag handling;
  delta-encoded nibbles for Manual with artifact/finger-out sentinels) — see
  `esp32/src/StoredRecordDownloader.cpp` for the ported implementation and the reference
  project's `src/pulseoxdl.c` (`process_nibbles_auto`/`process_nibbles_manual`) for the
  original logic this was ported from.
- Delete: `DELETE_RECORDS_AUTO` (`0x9d, 0x7f,0x7f,0x7f,0x7f, 0x00,0x00, 0x19`) or
  `DELETE_RECORD_MANUAL_0`/`DELETE_RECORD_MANUAL_1` (two-exchange sequence). Only sent
  when test mode is off, and only after the record's datums are already safely appended
  to the firmware's own CSV buffer.

## Licensing

`esp32/` is licensed **GPLv3** (see `esp32/COPYING`) because its stored-record decoding
logic is ported directly from the GPLv3-licensed `pulseoxdl` project. `android/` contains
no ported GPL code and is not affected by this.
