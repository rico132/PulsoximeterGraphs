#!/usr/bin/env python3
"""
ble_csv_sender.py — Push a CSV file into the Pulsoximeter Graphs app over BLE, without
the real ESP32 hardware.

The app only knows how to *pull* data: it's a BLE central that scans for a peripheral
called "PulsoxRelay" advertising a specific service UUID, connects, and speaks the
sync sequence in PROTOCOL.md (SET_TIME -> REQUEST_DATA -> stream CSV bytes over
notifications -> CLEAR_BUFFER). There's no "push a file to a phone" primitive in BLE —
so this script makes the laptop impersonate that ESP32 peripheral, GATT service and all,
so the app's existing "Sync via BLE" button pulls your CSV exactly like it would from
the real device.

Linux only: uses BlueZ's GATT-server support over D-Bus (via the `bless` library).
Windows/macOS peripheral-mode BLE is a different backend entirely and not covered here.

Setup (once):
    python3 -m venv .venv && source .venv/bin/activate
    pip install -r tools/requirements.txt

Usage:
    source .venv/bin/activate
    python3 tools/ble_csv_sender.py path/to/export.csv
    python3 tools/ble_csv_sender.py path/to/first.csv path/to/second.csv path/to/third.csv

Then open the app and tap the Bluetooth sync icon in the top bar. It scans, connects,
pulls the file(s), and this script exits on its own once the app confirms the import
(CLEAR_BUFFER) — Ctrl+C also stops it cleanly at any point.

Multiple files are sent as one transfer using the multi-file extension documented in
PROTOCOL.md (a small header naming each file's length, invisible to the ESP32, which never
sends it) — the app imports each file separately and reports one combined row count.

Speed:
- The defaults (--chunk-size 180, --chunk-delay 0.01) are a conservative middle ground.
  If a transfer feels slow, --chunk-size can go as high as ~500 (just under the 500-byte
  payload the app's requested 503-byte MTU allows) and --chunk-delay can go lower, even to
  0 — push both up/down together and re-run; if rows come out missing or corrupted in the
  app afterward, that combination was too aggressive for your adapter, so back off. Note
  that a *lower* chunk size always keeps working too, at any MTU: reassembly on the app
  side just concatenates notifications in arrival order until the terminator, so it never
  assumes chunks are any particular size — 500 is a ceiling, not a requirement.

Troubleshooting:
- "Could not locate bluetooth adapter" / D-Bus permission errors: make sure
  `bluetooth.service` is running (`systemctl status bluetooth`) and your user can
  manage it — try `sudo -E python3 tools/ble_csv_sender.py ...` (the `-E` keeps the
  venv on PATH) if a plain run gets a D-Bus access-denied error.
- App times out without ever seeing the device: turn off any other app/tool actively
  scanning or connecting over BLE at the same time (only one central can hold a GATT
  connection to this script at once), and confirm the laptop's Bluetooth is powered on
  (`bluetoothctl show` should say `Powered: yes`).
- Rows show up missing/corrupted in the app: retry with a smaller --chunk-size — some
  adapters silently truncate notifications that don't fit the negotiated MTU.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import struct
import sys
import time
from pathlib import Path
from typing import Any, Optional

if sys.platform != "linux":
    sys.exit(
        "ble_csv_sender.py only supports Linux (BlueZ). See PROTOCOL.md for the wire "
        "format if you need to port this to Windows/macOS."
    )

try:
    from bless import (
        BlessServer,
        BlessGATTCharacteristic,
        GATTAttributePermissions,
        GATTCharacteristicProperties,
    )
except ImportError:
    sys.exit("Missing dependency: pip install -r tools/requirements.txt")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ble_csv_sender")

# --- Mirrors android/app/.../data/ble/BleConstants.kt exactly — see PROTOCOL.md. ---
DEVICE_NAME = "PulsoxRelay"
SERVICE_UUID = "6f2a1000-2f5b-4a9c-91c4-0d8f6b1c9a01"
CONTROL_CHARACTERISTIC_UUID = "6f2a1001-2f5b-4a9c-91c4-0d8f6b1c9a01"
DATA_CHARACTERISTIC_UUID = "6f2a1002-2f5b-4a9c-91c4-0d8f6b1c9a01"

OPCODE_REQUEST_DATA = 0x01
OPCODE_SET_TIME = 0x02
OPCODE_CLEAR_BUFFER = 0x03
OPCODE_SET_TEST_MODE = 0x04
OPCODE_SET_WIFI_CREDENTIALS = 0x05
OPCODE_ENTER_OTA_MODE = 0x06

DATA_TERMINATOR = bytes([0x00])

# Multi-file extension (this script + the app only — see PROTOCOL.md). Never sent by the ESP32,
# which always transfers exactly one implicit file the legacy way.
MULTI_FILE_MAGIC = 0x02


def load_csv_bytes(csv_path: Path) -> bytes:
    """Reads the CSV and normalizes line endings to CRLF per PROTOCOL.md. The protocol
    requires senders to emit CRLF (readers on both sides must tolerate bare \\n, but
    there's no reason not to send the canonical form)."""
    text = csv_path.read_text(encoding="ascii", errors="strict")
    lines = text.splitlines()
    if not lines or not lines[0].strip().startswith("DATE,TIME,SPO2,PULSE"):
        log.warning(
            "First line doesn't look like the expected 'DATE,TIME,SPO2,PULSE' header "
            "— sending anyway."
        )
    return ("\r\n".join(lines) + "\r\n").encode("ascii")


def build_payload(csv_paths: list[Path]) -> bytes:
    """Loads every file and frames them per PROTOCOL.md's multi-file extension: a
    `[magic][fileCount][fileLength:u32 LE]*fileCount` header followed by the files'
    concatenated bytes. Used even for a single file, so the app's progress display (which
    needs the total byte count up front to show a percentage) always has it available —
    the header is a few bytes, negligible next to any real CSV."""
    files = [load_csv_bytes(p) for p in csv_paths]
    header = bytes([MULTI_FILE_MAGIC, len(files)]) + b"".join(
        struct.pack("<I", len(f)) for f in files
    )
    return header + b"".join(files)


class PulsoxRelay:
    """Emulates just enough of the ESP32's GATT server (PROTOCOL.md) to satisfy the
    app's BleGattClient sync sequence for one file."""

    def __init__(self, csv_bytes: bytes, chunk_size: int, chunk_delay: float, file_count: int):
        self.csv_bytes = csv_bytes
        self.chunk_size = chunk_size
        self.chunk_delay = chunk_delay
        self.file_count = file_count
        self.server: Optional[BlessServer] = None
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.done: Optional[asyncio.Event] = None
        # Set by watch_connection() (or send_csv() itself) the moment the phone drops the
        # connection before confirming CLEAR_BUFFER — most often because it hit its own
        # sync-timeout watchdog while waiting on us. Without this, a mid-transfer disconnect
        # went unnoticed: send_csv() kept blasting notifications with nothing subscribed to
        # receive them, and run() would then wait on `done` forever.
        self.failed: Optional[asyncio.Event] = None
        self.failure_reason: Optional[str] = None

    def read_request(self, characteristic: BlessGATTCharacteristic, **kwargs) -> bytearray:
        return characteristic.value

    def write_request(self, characteristic: BlessGATTCharacteristic, value: Any, **kwargs):
        characteristic.value = value
        if characteristic.uuid.lower() != CONTROL_CHARACTERISTIC_UUID or not value:
            return
        opcode = value[0]
        if opcode == OPCODE_REQUEST_DATA:
            log.info(
                "REQUEST_DATA received — sending %d bytes across %d file(s)",
                len(self.csv_bytes),
                self.file_count,
            )
            assert self.loop is not None
            self.loop.create_task(self.send_csv())
        elif opcode == OPCODE_SET_TIME:
            if len(value) >= 9:
                epoch = struct.unpack_from("<q", value, 1)[0]
                log.info("SET_TIME received (phone clock: epoch %d)", epoch)
        elif opcode == OPCODE_CLEAR_BUFFER:
            log.info("CLEAR_BUFFER received — the app confirmed the import. Done.")
            assert self.done is not None
            self.done.set()
        elif opcode == OPCODE_SET_TEST_MODE:
            log.info("SET_TEST_MODE received (ignored — nothing to preserve here)")
        elif opcode == OPCODE_SET_WIFI_CREDENTIALS:
            log.info("SET_WIFI_CREDENTIALS received (ignored — no OTA support in this script)")
        elif opcode == OPCODE_ENTER_OTA_MODE:
            log.info("ENTER_OTA_MODE received (ignored — no OTA support in this script)")
        else:
            log.warning("Unknown opcode 0x%02x", opcode)

    async def send_csv(self):
        assert self.server is not None and self.failed is not None
        data_char = self.server.get_characteristic(DATA_CHARACTERISTIC_UUID)
        total = len(self.csv_bytes)
        sent = 0
        last_print = 0.0
        for offset in range(0, total, self.chunk_size):
            # Checked before every chunk (not just left to the background watch_connection()
            # poll) so a mid-transfer disconnect stops the send loop immediately instead of
            # blasting the rest of the file's notifications at nobody.
            if not await self.server.is_connected():
                print()
                self._fail(f"Phone disconnected mid-transfer, at {sent}/{total} bytes sent")
                return
            chunk = self.csv_bytes[offset : offset + self.chunk_size]
            data_char.value = bytearray(chunk)
            self.server.update_value(SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
            sent += len(chunk)
            # Re-printed at most 10x/second rather than once per chunk — at a small
            # --chunk-size/--chunk-delay a multi-KB file is still hundreds of chunks/second,
            # and a print+flush syscall on every single one is real, avoidable overhead on the
            # one loop we actually want to run as fast as the link allows.
            now = time.monotonic()
            if now - last_print >= 0.1 or sent >= total:
                percent = sent * 100 // total if total else 100
                print(f"\r  sending... {percent:3d}% ({sent}/{total} bytes)", end="", flush=True)
                last_print = now
            # BlueZ's D-Bus notify path has no backpressure of its own — pacing chunks avoids
            # outrunning the link and dropping one, which the app would silently read as gappy
            # CSV. See the module docstring's "Speed" section for how far this can be pushed.
            if self.chunk_delay > 0:
                await asyncio.sleep(self.chunk_delay)
        print()  # end the progress line before the next log.info
        data_char.value = bytearray(DATA_TERMINATOR)
        self.server.update_value(SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
        log.info("Transfer complete, sent terminator. Waiting for the app's CLEAR_BUFFER...")

    async def watch_connection(self):
        """Runs for the whole session as a backstop alongside the inline check in send_csv():
        catches a disconnect that happens *between* chunks (e.g. while send_csv is asleep during
        chunk_delay, or after the terminator while we're waiting on CLEAR_BUFFER) instead of
        only at the top of the loop. `is_connected()` reflects whether the phone still has an
        active notify subscription on the Data characteristic, which BlueZ tears down as soon as
        the underlying connection drops."""
        assert self.done is not None and self.failed is not None
        was_connected = False
        while not self.done.is_set() and not self.failed.is_set():
            connected = await self.server.is_connected()
            if connected:
                was_connected = True
            elif was_connected:
                self._fail("Phone disconnected before confirming the import (CLEAR_BUFFER never arrived)")
                return
            await asyncio.sleep(1.0)

    def _fail(self, reason: str):
        assert self.failed is not None
        if self.failed.is_set():
            return
        self.failure_reason = reason
        log.error(
            "%s — it likely hit its own sync timeout waiting on us. Try a larger "
            "--chunk-size / smaller --chunk-delay, or check for other apps holding a BLE "
            "connection to this script.",
            reason,
        )
        self.failed.set()

    async def run(self, adapter: Optional[str]) -> bool:
        self.loop = asyncio.get_running_loop()
        self.done = asyncio.Event()
        self.failed = asyncio.Event()

        kwargs = {"adapter": adapter} if adapter else {}
        server = BlessServer(name=DEVICE_NAME, loop=self.loop, **kwargs)
        server.read_request_func = self.read_request
        server.write_request_func = self.write_request
        self.server = server

        await server.add_new_service(SERVICE_UUID)
        await server.add_new_characteristic(
            SERVICE_UUID,
            CONTROL_CHARACTERISTIC_UUID,
            GATTCharacteristicProperties.write
            | GATTCharacteristicProperties.write_without_response,
            None,
            GATTAttributePermissions.writeable,
        )
        await server.add_new_characteristic(
            SERVICE_UUID,
            DATA_CHARACTERISTIC_UUID,
            GATTCharacteristicProperties.notify | GATTCharacteristicProperties.read,
            bytearray(),
            GATTAttributePermissions.readable,
        )

        await server.start()
        log.info(
            "Advertising as %r (service %s) — open the app and tap the Bluetooth sync icon.",
            DEVICE_NAME,
            SERVICE_UUID,
        )

        watchdog = self.loop.create_task(self.watch_connection())
        try:
            done_task = self.loop.create_task(self.done.wait())
            failed_task = self.loop.create_task(self.failed.wait())
            await asyncio.wait({done_task, failed_task}, return_when=asyncio.FIRST_COMPLETED)
            for task in (done_task, failed_task):
                if not task.done():
                    task.cancel()
            return not self.failed.is_set()
        finally:
            watchdog.cancel()
            await asyncio.sleep(0.5)  # let the app's disconnect settle before tearing down
            await server.stop()
            log.info("Server stopped.")


def main():
    parser = argparse.ArgumentParser(
        description="Push a CSV file into the Pulsoximeter Graphs app over BLE by "
        "impersonating the PulsoxRelay ESP32 peripheral."
    )
    parser.add_argument(
        "csv_paths",
        type=Path,
        nargs="+",
        metavar="csv_path",
        help="CSV file(s) to send (DATE,TIME,SPO2,PULSE format). Multiple files are sent as "
        "one transfer; the app imports each separately and reports one combined row count.",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=180,
        help="Bytes per BLE notification (default: 180 — well under the ~500-byte payload "
        "the app's requested 503-byte MTU allows; raise it for speed, up to ~500, or lower "
        "it if the app reports dropped/corrupt rows)",
    )
    parser.add_argument(
        "--chunk-delay",
        type=float,
        default=0.01,
        help="Seconds to wait between notifications (default: 0.01; can go as low as 0 for "
        "max speed — see the module docstring's 'Speed' section)",
    )
    parser.add_argument(
        "--adapter",
        default=None,
        help="Bluetooth adapter to use, e.g. hci0 (default: whichever bless/BlueZ picks)",
    )
    args = parser.parse_args()

    for path in args.csv_paths:
        if not path.is_file():
            parser.error(f"{path} not found")
    # fileCount is a single byte in the multi-file header (see PROTOCOL.md) — this is far above
    # any real use of this tool, just a defensive bound so a typo'd glob doesn't silently truncate.
    if len(args.csv_paths) > 255:
        parser.error(f"too many files ({len(args.csv_paths)}) — the multi-file header caps this at 255")

    csv_bytes = build_payload(args.csv_paths)
    relay = PulsoxRelay(
        csv_bytes,
        chunk_size=args.chunk_size,
        chunk_delay=args.chunk_delay,
        file_count=len(args.csv_paths),
    )
    try:
        succeeded = asyncio.run(relay.run(args.adapter))
    except KeyboardInterrupt:
        return
    if not succeeded:
        sys.exit(1)


if __name__ == "__main__":
    main()
