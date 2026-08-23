package com.oxipulse.pulsoximetergraphs.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A second, independent BLE GATT client alongside [BleGattClient] — kept separate rather than
 * folded into it despite scanning for and pairing with the exact same "PulsoxRelay" device,
 * because the two flows barely overlap (this one never touches the Data characteristic or the
 * CSV import path at all) and interleaving two write/notify state machines inside one already
 * substantial [BleGattClient.gattCallback] risked destabilizing the well-exercised CSV sync
 * path for a feature that's inherently higher-risk to begin with (pushing arbitrary firmware).
 * A little duplicated connect/bond boilerplate is a fair trade for that isolation.
 *
 * Flow (see PROTOCOL.md §"BLE firmware update"):
 * 1. Scan, connect, pair if needed (same as [BleGattClient]), discover services.
 * 2. Write START_FIRMWARE_UPDATE ([size][expectedMd5Hex]).
 * 3. Write the image to the Firmware characteristic in `negotiatedMtu - 3`-byte chunks using
 *    write-without-response, queuing the next chunk as soon as the previous one is accepted
 *    into the local outgoing queue rather than waiting for an ATT-level round trip per chunk —
 *    see [sendNextChunk]'s own doc and `BleGattServer.cpp`'s matching characteristic-property
 *    comment for why this is still safe and ordered despite the lack of a per-chunk ack.
 * 4. Write FINISH_FIRMWARE_UPDATE once every chunk has been queued.
 * 5. Wait for a Status notification carrying the result. Success means the ESP32 has already
 *    switched its boot partition and is rebooting into the new image on its own — this client
 *    does not (and cannot) do anything further to make that happen.
 *
 * A third, unrelated admin action — [unpairAllDevices] — also lives here rather than in its own
 * class: it needs the exact same scan/connect/bond/discover machinery and nothing else, and
 * (like a version check) is a single write followed by an expected ESP32-initiated disconnect,
 * not a multi-step transfer — see [UnpairState]'s own doc.
 */
class BleFirmwareUpdateClient(private val context: Context) {

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Scanning : UpdateState
        data object Connecting : UpdateState
        data class Uploading(val bytesSent: Int, val totalBytes: Int) : UpdateState
        data object Verifying : UpdateState
        data object Success : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    sealed interface VersionCheckState {
        data object Idle : VersionCheckState
        data object Checking : VersionCheckState
        data class Checked(val version: String) : VersionCheckState
        data class Failed(val message: String) : VersionCheckState
    }

    /**
     * PROTOCOL.md's UNPAIR_ALL_DEVICES: deletes every BLE bond the ESP32 holds, including this
     * phone's own, then disconnects. [InProgress] covers the whole scan-through-write span (no
     * finer-grained steps, same granularity as [VersionCheckState.Checking]) — success is simply
     * the ESP32 acking the write, since the disconnect that follows is expected, not something to
     * wait on (see [onCharacteristicWrite]'s handling of it).
     */
    sealed interface UnpairState {
        data object Idle : UnpairState
        data object InProgress : UnpairState
        data object Success : UnpairState
        data class Failed(val message: String) : UnpairState
    }

    /** Which of [startUpdate]/[checkDeviceVersion]/[unpairAllDevices] the current connection is for. */
    private enum class Mode { NONE, CHECK_VERSION, UPDATE, UNPAIR_ALL }
    private var mode = Mode.NONE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _versionCheckState = MutableStateFlow<VersionCheckState>(VersionCheckState.Idle)
    val versionCheckState: StateFlow<VersionCheckState> = _versionCheckState.asStateFlow()

    private val _unpairState = MutableStateFlow<UnpairState>(UnpairState.Idle)
    val unpairState: StateFlow<UnpairState> = _unpairState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var firmwareCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private var firmwareBytes: ByteArray = ByteArray(0)
    private var expectedMd5Hex: String = ""
    private var bytesSent = 0
    private var chunkSize = BleConstants.DEFAULT_MTU - 3

    private var cancelled = false
    private var timeoutJob: Job? = null
    private var bondStateReceiver: BroadcastReceiver? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanCallback: ScanCallback? = null

    /**
     * [firmwareBytes] is sent exactly as given — callers must pass the full, unmodified `.bin`
     * downloaded from the release asset. [expectedMd5Hex] must be that same array's MD5, as a
     * lowercase or uppercase 32-char hex string (case doesn't matter — Update.h compares it
     * case-sensitively against its own always-lowercase digest internally, so this class always
     * lowercases it before sending, matching what a real image's hex digest naturally is).
     */
    @SuppressLint("MissingPermission") // Callers must check BlePermissions before calling.
    fun startUpdate(firmwareBytes: ByteArray, expectedMd5Hex: String) {
        if (mode != Mode.NONE) return // Already busy with an update or a version check.
        require(expectedMd5Hex.length == 32) { "expectedMd5Hex must be exactly 32 hex chars" }

        this.firmwareBytes = firmwareBytes
        this.expectedMd5Hex = expectedMd5Hex.lowercase()
        this.bytesSent = 0
        this.mode = Mode.UPDATE
        cancelled = false
        log("Firmware update started: ${firmwareBytes.size} bytes, md5=$expectedMd5Hex")
        _updateState.value = UpdateState.Scanning
        armTimeout()
        beginScan()
    }

    /**
     * Connects just far enough to read the ESP32's currently-running [Config.kFirmwareVersion]
     * (mirrored as [BleConstants.STATUS_TAG_FIRMWARE_VERSION]) off the Status characteristic,
     * then disconnects — used by the Settings "Check for update" flow to decide whether the
     * latest GitHub release is actually newer than what's already flashed, before ever
     * downloading anything.
     */
    @SuppressLint("MissingPermission")
    fun checkDeviceVersion() {
        if (mode != Mode.NONE) return // Already busy with an update or a version check.
        this.mode = Mode.CHECK_VERSION
        cancelled = false
        log("Checking device firmware version")
        _versionCheckState.value = VersionCheckState.Checking
        armTimeout()
        beginScan()
    }

    /**
     * Connects, then writes UNPAIR_ALL_DEVICES — see [UnpairState]'s own doc for what that does
     * and why success doesn't wait for the ESP32's own disconnect.
     */
    @SuppressLint("MissingPermission")
    fun unpairAllDevices() {
        if (mode != Mode.NONE) return // Already busy with an update, version check, or unpair.
        this.mode = Mode.UNPAIR_ALL
        cancelled = false
        log("Unpairing all devices")
        _unpairState.value = UnpairState.InProgress
        armTimeout()
        beginScan()
    }

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is not available or disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("BLE scanner unavailable")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScan()
                connect(result.device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE scan failed (code $errorCode)")
            }
        }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
    }

    fun cancelUpdate() {
        when (_updateState.value) {
            UpdateState.Scanning, UpdateState.Connecting, is UpdateState.Uploading, UpdateState.Verifying -> {
                log("Firmware update cancelled by user")
                cancelled = true
                cancelTimeout()
                // Sent best-effort: if this doesn't reach the ESP32 (link already gone), its own
                // onDisconnect handler frees the in-progress update anyway — see
                // BleGattServer::ServerCallbacks::onDisconnect's own comment.
                bluetoothGatt?.let { writeAbort(it) }
                _updateState.value = UpdateState.Idle
                disconnect()
            }
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        log("Found device, connecting to $address")
        // Shared by both startUpdate() (mode == UPDATE) and checkDeviceVersion() (mode ==
        // CHECK_VERSION) — see this class's own doc. Only UPDATE has a "Connecting" state to
        // report; VersionCheckState has no equivalent granular step, its Checking already covers
        // the whole scan-through-read span. Writing UpdateState.Connecting unconditionally here
        // used to leak into a plain version check: _updateState would get stuck on Connecting
        // once the check concluded (nothing resets it back to Idle the way fail()/success do for
        // whichever state is actually live for the current mode — see fail()'s own doc), which
        // left the Settings screen's "Download and install" button permanently disabled (it's
        // gated on updateState) after every "Check for update" tap, until the user hit Cancel
        // (whose handler explicitly resets UpdateState to Idle).
        if (mode == Mode.UPDATE) {
            _updateState.value = UpdateState.Connecting
        }
        val adapter = bluetoothAdapter ?: return fail("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Mirrors [BleGattClient.ensureBondedThenDiscoverServices] — see its own doc for why. */
    @SuppressLint("MissingPermission")
    private fun ensureBondedThenDiscoverServices(gatt: BluetoothGatt) {
        val device = gatt.device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            gatt.discoverServices()
            return
        }
        log("Not yet bonded — requesting pairing")
        registerBondStateReceiver(gatt)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            device.createBond()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerBondStateReceiver(gatt: BluetoothGatt) {
        if (bondStateReceiver != null) return
        val targetAddress = gatt.device.address
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device?.address != targetAddress) return

                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> {
                        unregisterBondStateReceiver()
                        armTimeout()
                        gatt.discoverServices()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        val previous = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                            BluetoothDevice.BOND_NONE,
                        )
                        if (previous == BluetoothDevice.BOND_BONDING) {
                            unregisterBondStateReceiver()
                            fail("Pairing failed or was cancelled")
                        }
                    }
                    else -> Unit
                }
            }
        }
        bondStateReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterBondStateReceiver() {
        bondStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — not an error, see BleGattClient's identical comment.
            }
        }
        bondStateReceiver = null
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        BleDebugLog.add("[fw-update] $message")
    }

    /**
     * Forces Android to discard whatever GATT attribute table it has cached for this device and
     * actually re-query the peripheral on the next discoverServices() call, rather than possibly
     * reusing a stale cache from an earlier connection. `BluetoothGatt.refresh()` isn't public
     * API — no direct binding exists on the class — but it's a real method present on every
     * AOSP-derived build; calling it via reflection is the standard, widely-used workaround for
     * exactly this (Nordic's and Polidea's BLE libraries, and most others, do the same thing).
     *
     * Needed here specifically because the Firmware characteristic this class looks for was
     * added to the ESP32 firmware later than Control/Data/Status, which a phone may already be
     * bonded with from earlier CSV-sync use (see BleGattClient). Android's GATT cache is keyed
     * by remote device identity, not by firmware version, and nothing on the ESP32 side emits a
     * GATT "Service Changed" indication (0x2A05) when its own attribute table grows across a
     * reflash — so an already-bonded phone can get stuck reusing a service list from before this
     * characteristic ever existed, even once the ESP32 has since been reflashed with firmware
     * that adds it. That reads as "Required characteristics not found" despite the ESP32
     * actually running fully up-to-date firmware. A failed reflection call (e.g. removed on some
     * future Android release) is treated as a no-op, not fatal — discoverServices() still runs
     * either way, just without the forced refresh.
     */
    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            gatt.javaClass.getMethod("refresh").invoke(gatt)
        } catch (e: Exception) {
            log("GATT cache refresh unavailable: ${e.javaClass.simpleName}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected (status $status)")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // See BleGattClient's identical call for why: ~doubles raw over-air bitrate when
                // both sides support it, falls back silently to 1M otherwise. A firmware image is
                // one to a few hundred KB sent in ~500-byte chunks, so this matters here at least
                // as much as it does for a CSV download.
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
                refreshGattCache(gatt)
                ensureBondedThenDiscoverServices(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                // A disconnect right after Verifying is the EXPECTED, successful outcome for an
                // update: the ESP32 reboots itself into the new firmware immediately after
                // reporting success (see BleGattServer::handleFinishFirmwareUpdate) rather than
                // waiting for the phone to disconnect first. A version check disconnects itself
                // deliberately once it has its answer (see onCharacteristicRead below), and an
                // unpair-all request disconnects itself the moment the write is acked (see
                // onCharacteristicWrite below) — the ESP32 also disconnects on its own right
                // after, but by then this side has already moved on. None of these are failures
                // — only an *unexpected* disconnect, before the relevant flow reached its own
                // success state, is.
                val alreadyConcluded = when (mode) {
                    Mode.UPDATE -> _updateState.value is UpdateState.Success || _updateState.value is UpdateState.Failed
                    Mode.CHECK_VERSION -> _versionCheckState.value is VersionCheckState.Checked ||
                        _versionCheckState.value is VersionCheckState.Failed
                    Mode.UNPAIR_ALL -> _unpairState.value is UnpairState.Success ||
                        _unpairState.value is UnpairState.Failed
                    Mode.NONE -> true
                }
                if (!cancelled && !alreadyConcluded) {
                    fail("Device disconnected unexpectedly")
                }
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status)")
                return
            }
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                fail("PulsoxRelay service not found")
                return
            }
            controlCharacteristic = service.getCharacteristic(BleConstants.CONTROL_CHARACTERISTIC_UUID)
            firmwareCharacteristic = service.getCharacteristic(BleConstants.FIRMWARE_CHARACTERISTIC_UUID)
            statusCharacteristic = service.getCharacteristic(BleConstants.STATUS_CHARACTERISTIC_UUID)
            if (controlCharacteristic == null || firmwareCharacteristic == null || statusCharacteristic == null) {
                fail("Required characteristics not found (is the ESP32 firmware up to date?)")
                return
            }
            armTimeout()
            gatt.requestMtu(BleConstants.REQUESTED_MTU)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU negotiated: $mtu (status $status)")
            chunkSize = (if (mtu > 3) mtu - 3 else BleConstants.DEFAULT_MTU - 3).coerceAtLeast(20)
            armTimeout()
            when (mode) {
                // No notifications needed for a single plain read — see checkDeviceVersion's own doc.
                Mode.CHECK_VERSION -> gatt.readCharacteristic(statusCharacteristic)
                Mode.UPDATE -> enableStatusNotifications(gatt)
                Mode.UNPAIR_ALL -> writeUnpairAllDevices(gatt)
                Mode.NONE -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            armTimeout()
            writeStartFirmwareUpdate(gatt)
        }

        // The 4-arg onCharacteristicRead(gatt, characteristic, value, status) overload (API 33+)
        // forwards to this deprecated 3-arg one by default, same as onCharacteristicChanged
        // below — see its own comment for why overriding this version keeps working correctly
        // on every API level from minSdk (31) up.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != BleConstants.STATUS_CHARACTERISTIC_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Reading firmware version failed (status $status)")
                return
            }
            val value = characteristic.value
            if (value == null || value.isEmpty() || value[0] != BleConstants.STATUS_TAG_FIRMWARE_VERSION) {
                fail("Unexpected Status characteristic response")
                return
            }
            val version = String(value, 1, value.size - 1, Charsets.US_ASCII)
            log("Device firmware version: $version")
            cancelTimeout()
            _versionCheckState.value = VersionCheckState.Checked(version)
            disconnect()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Write to ${characteristic.uuid} failed (status $status)")
                return
            }
            armTimeout()
            when (characteristic.uuid) {
                BleConstants.FIRMWARE_CHARACTERISTIC_UUID -> onChunkAcked(gatt)
                else -> when (lastControlWrite) {
                    ControlWrite.START_FIRMWARE_UPDATE -> {
                        _updateState.value = UpdateState.Uploading(0, firmwareBytes.size)
                        sendNextChunk(gatt)
                    }
                    ControlWrite.FINISH_FIRMWARE_UPDATE -> _updateState.value = UpdateState.Verifying
                    ControlWrite.UNPAIR_ALL_DEVICES -> {
                        // The ack IS the result here — there's no further notification to wait
                        // for (contrast FINISH_FIRMWARE_UPDATE's Status notification above). The
                        // ESP32 disconnects on its own right after processing this opcode, but
                        // this side already knows the outcome, so it disconnects proactively too
                        // rather than depending on that — see onConnectionStateChange's own doc.
                        log("Unpair-all acked by the ESP32")
                        cancelTimeout()
                        _unpairState.value = UnpairState.Success
                        disconnect()
                    }
                    else -> Unit
                }
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleConstants.STATUS_CHARACTERISTIC_UUID) return
            val value = characteristic.value ?: return
            onStatusNotification(value)
        }
    }

    private fun onChunkAcked(gatt: BluetoothGatt) {
        _updateState.value = UpdateState.Uploading(bytesSent, firmwareBytes.size)
        if (bytesSent >= firmwareBytes.size) {
            writeFinish(gatt)
        } else {
            sendNextChunk(gatt)
        }
    }

    private fun onStatusNotification(value: ByteArray) {
        if (value.isEmpty() || value[0] != BleConstants.STATUS_TAG_FIRMWARE_UPDATE_RESULT) return
        val success = value.size >= 2 && value[1] == 0x01.toByte()
        if (success) {
            log("Update succeeded — the ESP32 is rebooting into the new firmware")
            cancelTimeout()
            _updateState.value = UpdateState.Success
        } else {
            val errorCode = value.getOrNull(2)?.toInt()?.and(0xFF)
            fail(if (errorCode != null) "Update failed (error code $errorCode)" else "Update failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableStatusNotifications(gatt: BluetoothGatt) {
        val statusChar = statusCharacteristic ?: return fail("Status characteristic missing")
        val enabled = gatt.setCharacteristicNotification(statusChar, true)
        if (!enabled) {
            fail("Failed to enable Status notifications")
            return
        }
        val cccd = statusChar.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (cccd == null) {
            writeStartFirmwareUpdate(gatt)
            return
        }
        writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private enum class ControlWrite {
        START_FIRMWARE_UPDATE, FINISH_FIRMWARE_UPDATE, ABORT_FIRMWARE_UPDATE, UNPAIR_ALL_DEVICES
    }

    private var lastControlWrite: ControlWrite? = null

    @SuppressLint("MissingPermission")
    private fun writeStartFirmwareUpdate(gatt: BluetoothGatt) {
        val md5Bytes = expectedMd5Hex.toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(1 + 4 + 32).order(ByteOrder.LITTLE_ENDIAN)
            .put(BleConstants.OPCODE_START_FIRMWARE_UPDATE)
            .putInt(firmwareBytes.size)
            .put(md5Bytes)
            .array()
        lastControlWrite = ControlWrite.START_FIRMWARE_UPDATE
        writeControl(gatt, payload)
    }

    @SuppressLint("MissingPermission")
    private fun writeFinish(gatt: BluetoothGatt) {
        lastControlWrite = ControlWrite.FINISH_FIRMWARE_UPDATE
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_FINISH_FIRMWARE_UPDATE))
    }

    @SuppressLint("MissingPermission")
    private fun writeAbort(gatt: BluetoothGatt) {
        lastControlWrite = ControlWrite.ABORT_FIRMWARE_UPDATE
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_ABORT_FIRMWARE_UPDATE))
    }

    @SuppressLint("MissingPermission")
    private fun writeUnpairAllDevices(gatt: BluetoothGatt) {
        lastControlWrite = ControlWrite.UNPAIR_ALL_DEVICES
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_UNPAIR_ALL_DEVICES))
    }

    // WRITE_TYPE_NO_RESPONSE (see BleGattServer.cpp's matching WRITE_NR characteristic property
    // for the full reasoning): onCharacteristicWrite below still fires for this write type, but
    // as soon as the write is queued locally rather than after a full ATT-level round trip to
    // the ESP32 — that's what lets chunks flow back-to-back instead of one per BLE connection
    // interval, without giving up delivery ordering/reliability (both are guaranteed at the link
    // layer regardless of write type).
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun sendNextChunk(gatt: BluetoothGatt) {
        val characteristic = firmwareCharacteristic ?: return fail("Firmware characteristic missing")
        val end = (bytesSent + chunkSize).coerceAtMost(firmwareBytes.size)
        val chunk = firmwareBytes.copyOfRange(bytesSent, end)
        if (!writeFirmwareChunk(gatt, characteristic, chunk)) {
            // Android's local outgoing GATT queue is momentarily full — a real possibility now
            // that chunks are queued back-to-back rather than paced by a round trip each. Back
            // off briefly and retry the exact same chunk (bytesSent isn't advanced until a write
            // actually gets queued) rather than either losing it or busy-looping tightly.
            scope.launch {
                delay(CHUNK_QUEUE_RETRY_DELAY_MS)
                sendNextChunk(gatt)
            }
            return
        }
        bytesSent = end
    }

    /** True if `chunk` was successfully handed to the local Bluetooth stack's outgoing queue. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeFirmwareChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = chunk
            gatt.writeCharacteristic(characteristic)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeControl(gatt: BluetoothGatt, payload: ByteArray) {
        val characteristic = controlCharacteristic ?: return fail("Control characteristic missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun armTimeout() {
        cancelTimeout()
        // A version check or unpair-all request is meant to fail fast if the device just isn't
        // reachable right now (see checkDeviceVersion's own doc: the caller treats that as
        // "unknown," not an error, and shouldn't have to wait a full minute to find out) -- an
        // actual firmware push needs far more patience for a legitimately slow multi-hundred-KB
        // transfer.
        val timeoutMs = if (mode == Mode.UPDATE) UPDATE_TIMEOUT_MS else VERSION_CHECK_TIMEOUT_MS
        timeoutJob = scope.launch {
            delay(timeoutMs)
            fail(if (mode == Mode.UPDATE) "Firmware update timed out" else "Device unreachable")
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * Routes to whichever of [_updateState]/[_versionCheckState]/[_unpairState] the current
     * connection is actually for (see [mode]) — [startUpdate], [checkDeviceVersion], and
     * [unpairAllDevices] all share every bit of connect/bond/discover machinery below, and a
     * failure at any point in it (bonding, service discovery, MTU negotiation, a stall, ...) is
     * equally possible for any of them.
     *
     * No retry logic here unlike [BleGattClient.fail] — a partially-written firmware image left
     * on a stall is nothing to preserve or resume (the ESP32 only ever commits a *complete,
     * verified* image; see BleFirmwareUpdater's own doc), and neither a version check nor an
     * unpair-all request has anything at stake to protect either — so simply surfacing the
     * failure and letting the user explicitly retry is simpler and no less safe than automatic
     * retries would be.
     */
    private fun fail(message: String) {
        log("FAILED: $message")
        cancelTimeout()
        when (mode) {
            Mode.UPDATE -> _updateState.value = UpdateState.Failed(message)
            Mode.CHECK_VERSION -> _versionCheckState.value = VersionCheckState.Failed(message)
            Mode.UNPAIR_ALL -> _unpairState.value = UnpairState.Failed(message)
            Mode.NONE -> Unit
        }
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        unregisterBondStateReceiver()
        bluetoothGatt?.close()
        bluetoothGatt = null
        controlCharacteristic = null
        firmwareCharacteristic = null
        statusCharacteristic = null
        // Frees startUpdate()/checkDeviceVersion()'s own "already busy" guard now that this
        // connection (whichever it was for) is fully torn down.
        mode = Mode.NONE
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
        _versionCheckState.value = VersionCheckState.Idle
        _unpairState.value = UnpairState.Idle
    }

    companion object {
        private const val TAG = "BleFirmwareUpdateClient"

        // Generous: even with write-without-response chunking (see this class's own doc), a
        // multi-hundred-KB to low-single-digit-MB image can legitimately take a while on a slow
        // link. Re-armed on every chunk queued, so this is a stall timeout, not a fixed ceiling
        // on the whole transfer.
        private const val UPDATE_TIMEOUT_MS = 60_000L

        // A version check only ever does one scan + connect + bond(if needed) + one read, so
        // there's much less legitimate work to wait through — see armTimeout()'s own comment.
        private const val VERSION_CHECK_TIMEOUT_MS = 15_000L

        // How long to back off before retrying a chunk write whose local queue was full — see
        // sendNextChunk's own doc. Short on purpose: this is pacing against the local Bluetooth
        // stack briefly catching up, not a network-level retry.
        private const val CHUNK_QUEUE_RETRY_DELAY_MS = 10L
    }
}
