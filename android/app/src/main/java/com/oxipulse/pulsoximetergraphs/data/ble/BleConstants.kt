package com.oxipulse.pulsoximetergraphs.data.ble

import java.util.UUID

/**
 * Mirrors PROTOCOL.md exactly — the single source of truth shared with the ESP32 firmware
 * (`esp32/src/Config.h`). If the contract ever changes, update PROTOCOL.md first, then both
 * sides' constants files.
 */
object BleConstants {

    const val DEVICE_NAME = "PulsoxRelay"

    val SERVICE_UUID: UUID = UUID.fromString("6f2a1000-2f5b-4a9c-91c4-0d8f6b1c9a01")
    val CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f2a1001-2f5b-4a9c-91c4-0d8f6b1c9a01")
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f2a1002-2f5b-4a9c-91c4-0d8f6b1c9a01")

    /** Firmware version (read) + firmware-update result (notify) — see the opcodes below. */
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f2a1003-2f5b-4a9c-91c4-0d8f6b1c9a01")

    /**
     * Raw firmware-image bytes, phone -> ESP32, chunked the same way the Data characteristic
     * chunks CSV bytes ESP32 -> phone (each write <= negotiatedMtu - 3). See
     * [BleFirmwareUpdater] and PROTOCOL.md §"BLE firmware update (OTA over BLE)".
     */
    val FIRMWARE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f2a1004-2f5b-4a9c-91c4-0d8f6b1c9a01")

    /** Standard BLE Client Characteristic Configuration Descriptor, used to enable notifications. */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Control opcodes (1 byte, optional payload) ---

    /** No payload — ask the ESP32 to stream all buffered CSV rows over the Data characteristic. */
    const val OPCODE_REQUEST_DATA: Byte = 0x01

    /** Payload: 8 bytes, little-endian Unix epoch seconds. Sent on every connection. */
    const val OPCODE_SET_TIME: Byte = 0x02

    /** No payload — sent by the phone only after a successful local insert. */
    const val OPCODE_CLEAR_BUFFER: Byte = 0x03

    /** Payload: 1 byte, 0x00/0x01. Defaults to ON (0x01) on the ESP32 side. */
    const val OPCODE_SET_TEST_MODE: Byte = 0x04

    // 0x05 (SET_WIFI_CREDENTIALS) and 0x06 (ENTER_OTA_MODE) used to drive the now-removed
    // WiFi/ArduinoOTA update path — retired now that BLE OTA below is the only firmware-update
    // path, deliberately left unassigned rather than reused (see Config.h's matching comment).

    /**
     * Payload: `[size:u32 LE][expectedMd5Hex:32 ASCII bytes]`. Begins receiving a new firmware
     * image into the ESP32's inactive OTA partition — see [BleFirmwareUpdater].
     */
    const val OPCODE_START_FIRMWARE_UPDATE: Byte = 0x07

    /**
     * No payload — sent once exactly `size` bytes (from [OPCODE_START_FIRMWARE_UPDATE]) have
     * been written to [FIRMWARE_CHARACTERISTIC_UUID]. The ESP32 verifies size + MD5 and, only on
     * success, switches its boot partition to the new image and reboots — see
     * [STATUS_TAG_FIRMWARE_UPDATE_RESULT].
     */
    const val OPCODE_FINISH_FIRMWARE_UPDATE: Byte = 0x08

    /** No payload — discards an in-progress firmware update without touching the boot partition. */
    const val OPCODE_ABORT_FIRMWARE_UPDATE: Byte = 0x09

    /**
     * No payload — deletes every BLE bond the ESP32 currently holds, including this phone's own,
     * and regenerates its pairing PIN, then disconnects. Any phone must re-pair from scratch
     * afterward using the new PIN shown on the ESP32's serial log — the old PIN no longer works,
     * even for a phone that was already paired.
     */
    const val OPCODE_UNPAIR_ALL_DEVICES: Byte = 0x0A

    /** End-of-transfer marker on the Data characteristic: exactly one 0x00 byte. */
    val DATA_TERMINATOR: ByteArray = byteArrayOf(0x00)

    // --- Status characteristic notification/read tags (first byte of its value) ---

    /** Payload: the running firmware's version string, ASCII, not null-terminated. */
    const val STATUS_TAG_FIRMWARE_VERSION: Byte = 0x01

    /**
     * Payload: 1 byte, 0x01 success / 0x00 failure, then (failure only) 1 more byte: an
     * ESP32 Update.h `UPDATE_ERROR_*` code, or the sentinel 0xFF for a failure the ESP32 rejected
     * before ever touching flash (e.g. busy with a USB download, or one update already running).
     */
    const val STATUS_TAG_FIRMWARE_UPDATE_RESULT: Byte = 0x08

    /**
     * Payload: 1 byte, 0x01 = a USB re-download from the PO-400 has started and REQUEST_DATA's
     * dump is blocked waiting on it; 0x00 = that download finished. Sent once when the wait
     * begins, then again every ~5s for as long as it continues — see [BleGattClient]'s handling
     * of it for why (both showing the right status text and re-arming its inactivity timeout).
     */
    const val STATUS_TAG_USB_DOWNLOAD_STATE: Byte = 0x09

    /**
     * Multi-file transfer extension — app + `tools/ble_csv_sender.py` only, NOT part of the
     * ESP32 contract (the firmware always sends exactly one implicit file and never needs to
     * change). If the very first byte of the reassembled Data blob equals this magic value, what
     * follows is a header — `[fileCount:u8][fileLength:u32 LE]` repeated `fileCount` times —
     * before the concatenated raw bytes of each file in order; the existing single 0x00
     * terminator still marks the end of the whole transfer, unchanged. Any real CSV (from the
     * ESP32, or the sender script run against a single file with the legacy path) always starts
     * with a printable header byte, never this one, so a plain single-file transfer is completely
     * unaffected and doesn't need this byte examined at all — see PROTOCOL.md.
     */
    const val MULTI_FILE_MAGIC: Byte = 0x02

    /**
     * Requested MTU per PROTOCOL.md — 503 so a sender can chunk up to 500 bytes/notification
     * (well under NimBLE's own 527 ceiling and Android's accepted requestMtu() range); the
     * protocol must also work at the default MTU of 23, and at any smaller chunk size a sender
     * chooses, since reassembly on this side never assumes a fixed chunk length.
     */
    const val REQUESTED_MTU = 503

    /** Default (un-negotiated) BLE MTU, floor for chunk-size math. */
    const val DEFAULT_MTU = 23
}
