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

    /** Stretch, not MVP per PROTOCOL.md — declared for completeness, not currently read/used. */
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f2a1003-2f5b-4a9c-91c4-0d8f6b1c9a01")

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

    /** Payload: `[ssidLen:u8][ssid bytes][passLen:u8][pass bytes]`. */
    const val OPCODE_SET_WIFI_CREDENTIALS: Byte = 0x05

    /** No payload — ESP32 brings up WiFi and starts ArduinoOTA. */
    const val OPCODE_ENTER_OTA_MODE: Byte = 0x06

    /**
     * No payload — forgets which stored records this device-pairing already delivered and
     * re-downloads everything fresh from the still-attached PO-400 over USB, into the ESP32's
     * own relay buffer only (never touches the PO-400's own onboard storage). For recovering
     * after this app's own local copy is lost (e.g. app data cleared) — see [BleGattClient
     * .resyncFromDevice].
     */
    const val OPCODE_RESYNC_FROM_DEVICE: Byte = 0x07

    /** End-of-transfer marker on the Data characteristic: exactly one 0x00 byte. */
    val DATA_TERMINATOR: ByteArray = byteArrayOf(0x00)

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
