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
- No timezone information is carried. Every row comes from a downloaded stored record, so
  its date/time is the PO-400's own onboard clock at recording time, not the phone's — the
  firmware only opportunistically pushes the phone's `SET_TIME` (see below) onto the
  device's clock during its initial per-attach handshake, when available. This is a known
  limitation.

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
| `0x01` | `REQUEST_DATA` | none | Ask the ESP32 to re-download everything the still-attached PO-400 currently has (as if it had just been plugged in again) and stream all of it over the Data characteristic — see "Recommended sync sequence" below for why re-sending everything every time is deliberate, not a fixed-size incremental dump. |
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

### Multi-file extension (testing tool only — not part of the ESP32 contract)

`tools/ble_csv_sender.py` can send several CSV files in one transfer (for testing bulk imports).
This is layered on top of the Data characteristic's byte stream, invisible to the ESP32 firmware,
which never needs to change: if the very first byte of the reassembled blob equals `0x02`
(`BleConstants.MULTI_FILE_MAGIC`), what follows is a small header —

```
[0]:      0x02                     magic
[1]:      fileCount (u8)
[2..):    fileCount × u32 LE        each file's byte length, in order
```

— followed by the concatenated raw bytes of each file, then the same single `0x00` terminator as
always. Any real single-file transfer (from the ESP32, or the script run in its default legacy
path) always starts with a printable CSV header byte, never `0x02`, so this is fully backward
compatible and the common case needs no header at all.

### Recommended sync sequence (phone side)

1. Connect, discover services, request MTU 503.
2. Write `SET_TIME` (always, every connection).
3. Write `REQUEST_DATA`. The ESP32 re-downloads everything the PO-400 currently has over USB
   before streaming any of it — see `REQUEST_DATA`'s own table entry — which can take a while
   (the ESP32 waits up to 5 minutes internally); size any client-side inactivity timeout
   accordingly, and re-arm it on every Data notification, not just once at the start.
4. Reassemble Data notifications until the `0x00` terminator; parse the CSV blob.
5. **Drop any row whose timestamp already exists locally before inserting the rest** — every
   `REQUEST_DATA` returns the PO-400's *entire* current stored history, not just what's new
   since the last sync (the ESP32 keeps no notion of "already delivered" across syncs at all;
   test mode, on by default, is what keeps that history sitting on the device in the first
   place). Deduplicating here, not on the ESP32, is deliberate: it's the phone's own database
   that authoritatively knows what it already has, including after e.g. the app's local data
   being cleared and needing everything back — a case the ESP32 alone has no way to detect.
6. Only after the local insert succeeds, write `CLEAR_BUFFER`.

This ordering is what makes the protocol crash-safe: if the phone dies mid-insert, the
ESP32 still has the data next time. `CLEAR_BUFFER` itself is a pure housekeeping step — it lets
the ESP32 reclaim buffer space now that this transfer is confirmed durably stored — not a
"never send this again" marker; the very next `REQUEST_DATA`, from this or any other phone,
will include everything the PO-400 still has regardless of what's already been cleared before.

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

**Initial per-attach handshake** (sent once, before any stored-record exchange below):
mirrors the manufacturer software's own sequence, per pulseoxdl's `exchange.h` comment
("the sequence of exchanges that manufacturer's software does with the device every time
on initial communication"). The PO-400 otherwise defaults to spontaneously streaming its
own live-reading reports over the same interrupt endpoint used for the request/response
exchanges below — `STOP_SENDING_DATA` silences that, which is what actually makes
`STORED_PRESENT`/`GET_RECORD_METADATA_*` reliable instead of racing unsolicited live
packets.
- `STOP_SENDING_DATA`: write the fixed 18-byte command
  `7D 81 A7 80 80 80 80 80 80 7D 81 A2 80 80 80 80 80 80`.
- Two unidentified status queries: write `0x82, 0x02`, then `0x80, 0x00`. Sent
  unconditionally by the manufacturer software with no documented side effect.
- `SYNCHRONIZE_DEVICE_DATE_AND_TIME` (opcode `0x83`, followed by `%y%m%d%H%M%S`, 2
  unclear-but-always-zero bytes, and a checksum): only sent when the firmware already has
  a trustworthy time (i.e. the phone has sent `SET_TIME` at least once) — writing a bogus
  time to the device's onboard clock would be worse than skipping this step, and
  pulseoxdl's own comment calls it "probably not strictly necessary."
- `USER_NAME` (`0x8e, 0x03, 0x11`) and `MODEL_NAME` (`0x81, 0x01`): read-only identity
  queries, response content not otherwise used.

**Stored records** (Auto/Manual mode, device's own onboard memory):
- `STORED_PRESENT`: write `0x9f, 0x1f` → response indicates presence + Auto-vs-Manual mode.
- `GET_RECORD_METADATA_AUTO`: `0x9c, 0x01, 0x1d`. `GET_RECORD_METADATA_MANUAL`: `0xa0, 0x00, 0x20`.
  Gives start time (`%y%m%d%H%M%S`) and datum count (3×7-bit bytes, LSB order). For Auto mode
  with multiple stored records, send this **repeatedly, back-to-back, for every record until
  the response's "is last" byte is set — before requesting any record's datums**. Confirmed
  against real hardware: interleaving a datum-download request between
  `GET_RECORD_METADATA_AUTO` calls (i.e. enumerate-then-download one record at a time) corrupts
  the very first record's datum stream, even though its own metadata decodes correctly — the
  device evidently keeps an internal enumeration cursor that a datum request disturbs mid-
  listing. Mirrors pulseoxdl's own `process_stored()`, which completes its whole
  `set_record_metadata()` enumeration loop before any `extract_datums()` call.
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
