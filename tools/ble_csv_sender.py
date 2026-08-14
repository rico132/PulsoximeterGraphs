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

Then open the app and tap the Bluetooth sync icon in the top bar. It scans, connects,
pulls the file, and this script exits on its own once the app confirms the import
(CLEAR_BUFFER) — Ctrl+C also stops it cleanly at any point.

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


class PulsoxRelay:
    """Emulates just enough of the ESP32's GATT server (PROTOCOL.md) to satisfy the
    app's BleGattClient sync sequence for one file."""

    def __init__(self, csv_bytes: bytes, chunk_size: int, chunk_delay: float):
        self.csv_bytes = csv_bytes
        self.chunk_size = chunk_size
        self.chunk_delay = chunk_delay
        self.server: Optional[BlessServer] = None
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.done: Optional[asyncio.Event] = None

    def read_request(self, characteristic: BlessGATTCharacteristic, **kwargs) -> bytearray:
        return characteristic.value

    def write_request(self, characteristic: BlessGATTCharacteristic, value: Any, **kwargs):
        characteristic.value = value
        if characteristic.uuid.lower() != CONTROL_CHARACTERISTIC_UUID or not value:
            return
        opcode = value[0]
        if opcode == OPCODE_REQUEST_DATA:
            log.info("REQUEST_DATA received — sending %d bytes of CSV", len(self.csv_bytes))
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
        assert self.server is not None
        data_char = self.server.get_characteristic(DATA_CHARACTERISTIC_UUID)
        for offset in range(0, len(self.csv_bytes), self.chunk_size):
            chunk = self.csv_bytes[offset : offset + self.chunk_size]
            data_char.value = bytearray(chunk)
            self.server.update_value(SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
            # BlueZ's D-Bus notify path has no backpressure of its own — pacing chunks
            # avoids outrunning the link and dropping one, which the app would silently
            # read as gappy CSV.
            await asyncio.sleep(self.chunk_delay)
        data_char.value = bytearray(DATA_TERMINATOR)
        self.server.update_value(SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
        log.info("Transfer complete, sent terminator. Waiting for the app's CLEAR_BUFFER...")

    async def run(self, adapter: Optional[str]):
        self.loop = asyncio.get_running_loop()
        self.done = asyncio.Event()

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

        try:
            await self.done.wait()
        finally:
            await asyncio.sleep(0.5)  # let the app's disconnect settle before tearing down
            await server.stop()
            log.info("Server stopped.")


def main():
    parser = argparse.ArgumentParser(
        description="Push a CSV file into the Pulsoximeter Graphs app over BLE by "
        "impersonating the PulsoxRelay ESP32 peripheral."
    )
    parser.add_argument("csv_path", type=Path, help="CSV file to send (DATE,TIME,SPO2,PULSE format)")
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=100,
        help="Bytes per BLE notification (default: 100 — comfortably under the app's "
        "requested 247-byte MTU; lower it if the app reports dropped/corrupt rows)",
    )
    parser.add_argument(
        "--chunk-delay",
        type=float,
        default=0.02,
        help="Seconds to wait between notifications (default: 0.02)",
    )
    parser.add_argument(
        "--adapter",
        default=None,
        help="Bluetooth adapter to use, e.g. hci0 (default: whichever bless/BlueZ picks)",
    )
    args = parser.parse_args()

    if not args.csv_path.is_file():
        parser.error(f"{args.csv_path} not found")

    csv_bytes = load_csv_bytes(args.csv_path)
    relay = PulsoxRelay(csv_bytes, chunk_size=args.chunk_size, chunk_delay=args.chunk_delay)
    try:
        asyncio.run(relay.run(args.adapter))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
