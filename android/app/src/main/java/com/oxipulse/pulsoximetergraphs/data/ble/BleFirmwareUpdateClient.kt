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
 * 3. Write the image to the Firmware characteristic in `negotiatedMtu - 3`-byte chunks, one at a
 *    time, waiting for each write's ack before sending the next — this is what paces the phone
 *    to the ESP32's actual flash-write speed instead of flooding the link with no backpressure.
 * 4. Write FINISH_FIRMWARE_UPDATE once every chunk has been acked.
 * 5. Wait for a Status notification carrying the result. Success means the ESP32 has already
 *    switched its boot partition and is rebooting into the new image on its own — this client
 *    does not (and cannot) do anything further to make that happen.
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

    /** Which of [startUpdate]/[checkDeviceVersion] the current connection is for — see their own docs. */
    private enum class Mode { NONE, CHECK_VERSION, UPDATE }
    private var mode = Mode.NONE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _versionCheckState = MutableStateFlow<VersionCheckState>(VersionCheckState.Idle)
    val versionCheckState: StateFlow<VersionCheckState> = _versionCheckState.asStateFlow()

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

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected (status $status)")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                ensureBondedThenDiscoverServices(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                // A disconnect right after Verifying is the EXPECTED, successful outcome for an
                // update: the ESP32 reboots itself into the new firmware immediately after
                // reporting success (see BleGattServer::handleFinishFirmwareUpdate) rather than
                // waiting for the phone to disconnect first. A version check disconnects itself
                // deliberately once it has its answer (see onCharacteristicRead below). Neither
                // is a failure — only an *unexpected* disconnect, before either flow reached its
                // own success state, is.
                val alreadyConcluded = when (mode) {
                    Mode.UPDATE -> _updateState.value is UpdateState.Success || _updateState.value is UpdateState.Failed
                    Mode.CHECK_VERSION -> _versionCheckState.value is VersionCheckState.Checked ||
                        _versionCheckState.value is VersionCheckState.Failed
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

    private enum class ControlWrite { START_FIRMWARE_UPDATE, FINISH_FIRMWARE_UPDATE, ABORT_FIRMWARE_UPDATE }

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

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun sendNextChunk(gatt: BluetoothGatt) {
        val characteristic = firmwareCharacteristic ?: return fail("Firmware characteristic missing")
        val end = (bytesSent + chunkSize).coerceAtMost(firmwareBytes.size)
        val chunk = firmwareBytes.copyOfRange(bytesSent, end)
        bytesSent = end
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
        // A version check is meant to fail fast if the device just isn't reachable right now
        // (see checkDeviceVersion's own doc: the caller treats that as "unknown," not an error,
        // and shouldn't have to wait a full minute to find out) -- an actual firmware push needs
        // far more patience for a legitimately slow multi-hundred-KB transfer.
        val timeoutMs = if (mode == Mode.CHECK_VERSION) VERSION_CHECK_TIMEOUT_MS else UPDATE_TIMEOUT_MS
        timeoutJob = scope.launch {
            delay(timeoutMs)
            fail(if (mode == Mode.CHECK_VERSION) "Device unreachable" else "Firmware update timed out")
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * Routes to whichever of [_updateState]/[_versionCheckState] the current connection is
     * actually for (see [mode]) — both [startUpdate] and [checkDeviceVersion] share every bit of
     * connect/bond/discover machinery below, and a failure at any point in it (bonding, service
     * discovery, MTU negotiation, a stall, ...) is equally possible for either.
     *
     * No retry logic here unlike [BleGattClient.fail] — a partially-written firmware image left
     * on a stall is nothing to preserve or resume (the ESP32 only ever commits a *complete,
     * verified* image; see BleFirmwareUpdater's own doc), and a version check has nothing at
     * stake to protect either — so simply surfacing the failure and letting the user explicitly
     * retry is simpler and no less safe than automatic retries would be.
     */
    private fun fail(message: String) {
        log("FAILED: $message")
        cancelTimeout()
        when (mode) {
            Mode.UPDATE -> _updateState.value = UpdateState.Failed(message)
            Mode.CHECK_VERSION -> _versionCheckState.value = VersionCheckState.Failed(message)
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
    }

    companion object {
        private const val TAG = "BleFirmwareUpdateClient"

        // Generous: a multi-hundred-KB to low-single-digit-MB image, sent one small chunk at a
        // time with a full write-then-ack round trip per chunk (see this class's own doc for
        // why), can legitimately take well over a minute on a slow link. Re-armed on every
        // chunk ack, so this is a stall timeout, not a fixed ceiling on the whole transfer.
        private const val UPDATE_TIMEOUT_MS = 60_000L

        // A version check only ever does one scan + connect + bond(if needed) + one read, so
        // there's much less legitimate work to wait through — see armTimeout()'s own comment.
        private const val VERSION_CHECK_TIMEOUT_MS = 15_000L
    }
}
