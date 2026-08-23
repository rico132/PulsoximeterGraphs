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
| `6f2a1001-2f5b-4a9c-91c4-0d8f6b1c9a01` | Control | write-with-response, encrypted+authenticated | opcodes below |
| `6f2a1002-2f5b-4a9c-91c4-0d8f6b1c9a01` | Data | notify, encrypted+authenticated | chunked CSV bytes |
| `6f2a1003-2f5b-4a9c-91c4-0d8f6b1c9a01` | Status | read/notify, encrypted+authenticated | firmware version (read), firmware-update result (notify) |
| `6f2a1004-2f5b-4a9c-91c4-0d8f6b1c9a01` | Firmware | write-without-response, encrypted+authenticated | chunked firmware-image bytes, phone → ESP32 |

### BLE pairing

All characteristics require a bonded, encrypted, MITM-protected (authenticated) link —
without this, anyone within BLE range could connect to a "PulsoxRelay" and read someone's
SpO2/pulse history with zero access control. The ESP32 requires LE Secure Connections pairing
(`NimBLEDevice::setSecurityAuth(bonding=true, mitm=true, sc=true)`) with passkey entry
(`BLE_HS_IO_DISPLAY_ONLY`): the phone's OS shows its own native "enter PIN" pairing dialog on
first connection to a given ESP32, before any GATT operation against these characteristics can
succeed (a write/subscribe attempt beforehand fails with an ATT "insufficient authentication"
error).

The PIN itself is **not a fixed value in this document or in source** — a hardcoded literal
committed to a shared repo would stop being a secret at all. Instead the ESP32 generates a random
6-digit PIN on first boot ever (or after an NVS wipe), persists it in NVS (survives every
subsequent reboot/power cycle unchanged), and prints it to its serial log on every boot. It can be
regenerated at any time by sending a bare `blepin` (no argument — a replacement PIN is always
randomly generated, never operator-chosen, exactly like first-boot provisioning) over the serial
debug console — deliberately never reachable via any BLE opcode, so a not-yet-paired attacker can
never set their own known PIN. Regenerating the PIN only affects *future* pairings; phones
already bonded are unaffected, since the PIN is only used during the initial pairing handshake.

Once bonded, a phone reconnects automatically without re-pairing (the link's encryption keys are
cached by both sides) — the PIN only needs to be entered once per phone.

### Control opcodes (1 byte, optional payload)

| Opcode | Name | Payload | Meaning |
|---|---|---|---|
| `0x01` | `REQUEST_DATA` | none | Ask the ESP32 to stream its currently-buffered CSV rows over the Data characteristic. If the buffer has already been read out by an earlier `REQUEST_DATA` since the last download, this first re-downloads everything the still-attached PO-400 currently has (as if it had just been plugged in again) before streaming — see "Recommended sync sequence" below for why re-sending everything is deliberate, not a fixed-size incremental dump. A buffer nothing has read yet (freshly downloaded at boot, or by a real attach/re-attach) is streamed as-is, with no redundant extra download first. |
| `0x02` | `SET_TIME` | 8 bytes, little-endian Unix epoch seconds | Phone pushes its current clock to the ESP32 (which has no RTC/NTP). Sent on every connection. |
| `0x03` | `CLEAR_BUFFER` | none | Phone confirms it has durably stored the data it just received; ESP32 may discard its buffered copy, and (only with test mode off) deletes the matching records from the PO-400 itself. Must only be sent **after** a successful local insert. |
| `0x04` | `SET_TEST_MODE` | 1 byte, `0x00` or `0x01` | When `0x01` (on), the ESP32 never deletes downloaded stored records from the PO-400. Persisted in NVS. **Defaults to `0x01` (on/non-destructive)** until explicitly turned off. |
| `0x07` | `START_FIRMWARE_UPDATE` | `[size:u32 LE][expectedMd5Hex:32 ASCII bytes]` | Begin receiving a new firmware image into the ESP32's *inactive* OTA partition — see "BLE firmware update" below. Refused (result reported immediately, see the Status characteristic below) if a USB stored-record download is in progress, or an update is already under way. |
| `0x08` | `FINISH_FIRMWARE_UPDATE` | none | Sent once exactly `size` bytes have been written to the Firmware characteristic. Verifies size + MD5 and, **only on success**, switches the boot partition to the new image and reboots. |
| `0x09` | `ABORT_FIRMWARE_UPDATE` | none | Discards an in-progress firmware update without touching the boot partition. |

`0x05` and `0x06` used to be `SET_WIFI_CREDENTIALS`/`ENTER_OTA_MODE`, driving a WiFiManager +
`ArduinoOTA` WiFi update path — removed now that BLE firmware update below is the only
firmware-update mechanism. Deliberately left unassigned rather than reused.

### BLE firmware update (OTA over BLE)

The phone downloads a firmware image (see "Firmware release asset" below) and pushes it directly
over the same BLE link already used for CSV syncs — no WiFi credentials or separate network of
any kind involved. Uses ESP32's dual-partition OTA mechanism (`app0`/`app1` in
`esp32/partitions_8MB.csv`, via Arduino-ESP32's `Update` library/`esp_ota_*`): the image is
written entirely into the *inactive* partition while the current firmware keeps running from the
active one, and the boot partition is
flipped to the new image **only if `Update::end()` considers the write completely valid** (right
size, matching MD5) — a failed or abandoned update leaves the currently-running, already-proven-
bootable firmware completely untouched.

Sequence (phone side):

1. Compute the image's MD5 (32-char lowercase hex) locally, from the exact bytes about to be
   sent — this is what the ESP32 verifies against, so it catches download corruption too, not
   just whatever might go wrong over BLE itself.
2. Write `START_FIRMWARE_UPDATE` with the image's total size and that MD5.
3. Write the image to the Firmware characteristic in `negotiatedMtu - 3`-byte chunks, using
   **write-without-response** — queuing the next chunk as soon as the previous one is locally
   queued, with no per-chunk ATT-level round trip (unlike Control/`START_FIRMWARE_UPDATE` above,
   which does wait for its ack). This relies on BLE's link layer already being a reliable,
   in-order transport (write-without-response only skips the *application-level* ack, not
   delivery guarantees) and on the ESP32 processing writes for one connection strictly in
   arrival order — see `BleGattServer.cpp`'s own comment on the Firmware characteristic for the
   full reasoning, including why this can't reorder ahead of the `FINISH_FIRMWARE_UPDATE` write
   that follows it.
4. Once every chunk has been acknowledged, write `FINISH_FIRMWARE_UPDATE`.
5. Wait for a Status notification (see below) carrying the result. On success, the ESP32 has
   already switched its boot partition and reboots on its own — the phone does not, and cannot,
   do anything further to make that happen. On failure, the ESP32 keeps running its current
   firmware unchanged and does not reboot.

A disconnect at any point during an update (phone crash, out of range, ...) is handled the same
as an explicit `ABORT_FIRMWARE_UPDATE`: the ESP32 discards the partial write and frees the
in-progress `Update` handle so a future update attempt isn't refused as "already in progress" —
see `BleGattServer::ServerCallbacks::onDisconnect`.

### Status characteristic

- **Read**: always returns `[0x01][versionBytes...]` — the firmware's own compiled-in version
  string (`Config::kFirmwareVersion`, ASCII, not null-terminated), tag `0x01`
  (`STATUS_TAG_FIRMWARE_VERSION`). Set at build time (see "Firmware release asset" below); a
  locally-flashed dev build reports `"dev"`.
- **Notify**: `[0x08][success:u8][errorCode:u8 if success==0x00]` — sent exactly once per firmware
  update attempt, tag `0x08` (`STATUS_TAG_FIRMWARE_UPDATE_RESULT`, deliberately the same numeric
  value as the `FINISH_FIRMWARE_UPDATE` opcode — the two are never ambiguous, since one only ever
  flows phone→ESP32 and the other only ESP32→phone). `errorCode` is either one of Arduino-ESP32's
  `Update.h` `UPDATE_ERROR_*` codes, or the sentinel `0xFF` for a failure the ESP32 rejected
  before ever touching flash (busy with a USB download, or an update already in progress).

### Firmware release asset

`.github/workflows/android-release.yml` builds the ESP32 firmware (env `esp32-s3-devkitc-1`) with
`-D FIRMWARE_VERSION="<release tag>"` and attaches the resulting `firmware-esp32-s3-devkitc-1.bin`
to the same GitHub release it already publishes the signed APK to on every push to `main` — one
release, two assets. The Android app's "Check for update" (Settings → Device) reads the latest
release's tag and firmware asset via GitHub's REST API, compares the tag against the connected
ESP32's own reported version (Status characteristic read), and offers to download + push the
asset over BLE if they differ.

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

1. Connect. If not already bonded with this ESP32, pair first (see "BLE pairing" above) — the
   phone's OS handles the PIN-entry dialog natively — then discover services and request MTU 503.
2. Write `SET_TIME` (always, every connection).
3. Write `REQUEST_DATA`. This may first re-download everything the PO-400 currently has over USB
   before streaming any of it — see `REQUEST_DATA`'s own table entry for exactly when — which can
   take a while (the ESP32 waits up to 5 minutes internally); size any client-side inactivity
   timeout accordingly, and re-arm it on every Data notification, not just once at the start.
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
ESP32 still has the data next time. `CLEAR_BUFFER` lets the ESP32 reclaim buffer space now that
this transfer is confirmed durably stored, and — only with test mode off — is also what actually
triggers deleting the matching records from the PO-400 itself (see "PO-400 USB HID protocol"
below's "Delete" entry); it is not a "never send this again" marker either way: the very next
`REQUEST_DATA`, from this or any other phone, will include everything the PO-400 still has
(nothing, if it was just deleted; the same records again, if test mode kept them).

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
  `DELETE_RECORD_MANUAL_0`/`DELETE_RECORD_MANUAL_1` (two-exchange sequence). Only sent when
  test mode is off, and only once the *phone* confirms durable receipt of the buffer these
  records were appended to — i.e. in response to `CLEAR_BUFFER`, not immediately after the
  USB download itself. "Appended to the firmware's own CSV buffer" alone isn't a strong
  enough guarantee to justify deleting the PO-400's own copy — that buffer could still be
  lost (a crash, a reboot before BLE ever syncs it out) with nothing else left holding the
  data. Test mode defaults on specifically so this never actually happens without it being
  deliberately turned off first.

## Licensing

`esp32/` is licensed **GPLv3** (see `esp32/COPYING`) because its stored-record decoding
logic is ported directly from the GPLv3-licensed `pulseoxdl` project. `android/` contains
no ported GPL code and is not affected by this.
