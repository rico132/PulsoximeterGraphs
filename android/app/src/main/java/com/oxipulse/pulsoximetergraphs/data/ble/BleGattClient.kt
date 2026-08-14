package com.oxipulse.pulsoximetergraphs.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository
import java.io.ByteArrayOutputStream
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
 * BLE GATT client implementing exactly the sync sequence from PROTOCOL.md:
 *
 * 1. Scan by service UUID, connect, discover services.
 * 2. Request MTU 503 (falls back gracefully to whatever is negotiated, including the
 *    un-negotiated default of 23 — chunk math always uses the actual negotiated value).
 * 3. Write SET_TIME (every connection).
 * 4. Write REQUEST_DATA.
 * 5. Reassemble Data notifications until the single-0x00-byte terminator.
 * 6. Parse + insert the CSV blob via [ReadingsRepository.importCsv].
 * 7. Only after that insert succeeds, write CLEAR_BUFFER.
 *
 * This ordering is what makes the protocol crash-safe (see PROTOCOL.md): if step 6 never
 * completes, the ESP32 still has the data next time. A [SYNC_TIMEOUT_MS] watchdog guarantees
 * that a stalled connection can't wedge the UI in a "syncing" state forever — it's an
 * *inactivity* timeout, re-armed by [armTimeout] on every write ack and every Data chunk
 * received (not a single fixed deadline for the whole sync), since a large CSV can legitimately
 * take longer than [SYNC_TIMEOUT_MS] to transfer in full as long as bytes keep arriving.
 */
class BleGattClient(
    private val context: Context,
    private val readingsRepository: ReadingsRepository,
) {

    sealed interface SyncState {
        data object Idle : SyncState
        data object Scanning : SyncState
        data object Connecting : SyncState
        data object RequestingData : SyncState
        data class ReceivingData(val bytesReceived: Int) : SyncState
        data object Inserting : SyncState
        data object ClearingBuffer : SyncState
        data class Success(val rowsInserted: Int, val rowsSkipped: Int) : SyncState
        data class Failed(val message: String) : SyncState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Best-effort local mirror of the ESP32's test-mode flag: the Status characteristic that
     * would let us read it back is stretch/not-MVP per PROTOCOL.md, so we track only the last
     * value *this app* has sent. Null until the app has sent (or read) a value this session.
     */
    private val _testModeEnabled = MutableStateFlow<Boolean?>(null)
    val testModeEnabled: StateFlow<Boolean?> = _testModeEnabled.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null

    private val receiveBuffer = ByteArrayOutputStream()
    private var timeoutJob: Job? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanCallback: ScanCallback? = null

    /** Kicks off a full sync: scan -> connect -> SET_TIME -> REQUEST_DATA -> insert -> CLEAR_BUFFER. */
    @SuppressLint("MissingPermission") // Callers must check BlePermissions before calling.
    fun startSync() {
        if (_syncState.value !is SyncState.Idle &&
            _syncState.value !is SyncState.Success &&
            _syncState.value !is SyncState.Failed
        ) {
            return // A sync is already in progress.
        }

        receiveBuffer.reset()
        lastProgressEmitMs = 0L
        _syncState.value = SyncState.Scanning
        armTimeout()

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

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        _syncState.value = SyncState.Connecting
        val adapter = bluetoothAdapter ?: return fail("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Without this, Android negotiates its default "balanced" connection interval
                // (tens of ms per connection event), which caps real throughput far below what
                // the negotiated MTU/chunk size would allow — raising MTU alone doesn't touch
                // this ceiling. Requesting HIGH here (shortest interval Android permits) is what
                // actually speeds up the transfer; it must happen as early as possible since the
                // renegotiation itself takes a moment to take effect.
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (_syncState.value !is SyncState.Success && _syncState.value !is SyncState.Failed) {
                    fail("Device disconnected before sync completed")
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
            dataCharacteristic = service.getCharacteristic(BleConstants.DATA_CHARACTERISTIC_UUID)
            if (controlCharacteristic == null || dataCharacteristic == null) {
                fail("Required characteristics not found")
                return
            }
            armTimeout()
            gatt.requestMtu(BleConstants.REQUESTED_MTU)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Whether or not the negotiation succeeded, proceed with whatever MTU we have —
            // the protocol must also work correctly at the default, un-negotiated MTU of 23.
            armTimeout()
            enableDataNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            armTimeout()
            writeSetTime(gatt)
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
            when (lastControlWrite) {
                ControlWrite.SET_TIME -> writeRequestData(gatt)
                ControlWrite.REQUEST_DATA -> _syncState.value = SyncState.ReceivingData(0)
                ControlWrite.CLEAR_BUFFER -> {
                    cancelTimeout()
                    disconnect()
                }
                else -> Unit
            }
        }

        // The 3-arg onCharacteristicChanged(gatt, characteristic, value) overload (API 33+)
        // forwards to this one by default, so overriding this deprecated 2-arg version keeps
        // working correctly on every API level from minSdk (31) up — see Android's own
        // BluetoothGattCallback source for that forwarding behavior.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleConstants.DATA_CHARACTERISTIC_UUID) return
            val value = characteristic.value ?: return
            onDataChunk(value)
        }
    }

    private fun onDataChunk(value: ByteArray) {
        // Every chunk received is forward progress, so the watchdog resets here too — a large
        // CSV sent in many small notifications must not time out purely because the transfer as
        // a whole runs past SYNC_TIMEOUT_MS, only if it actually stalls.
        armTimeout()
        if (value.size == 1 && value[0] == BleConstants.DATA_TERMINATOR[0]) {
            onTransferComplete()
            return
        }
        receiveBuffer.write(value)
        // GATT callbacks are delivered on the main thread (no Handler/Executor was passed to
        // connectGatt), which is also where Compose recomposes. ReceivingData is a data class
        // keyed on the byte count, so emitting it on every single notification forced a full
        // recomposition (+ a LaunchedEffect(syncState) relaunch in GraphScreen) per chunk — with
        // a fast link, that main-thread work becomes the actual bottleneck: the UI can't keep up
        // with notification arrival, so the app lags behind the radio instead of the other way
        // around. Throttle to ~10 updates/sec, same as the sender script already throttles its
        // own progress print (see ble_csv_sender.py's send_csv) — the buffer itself still
        // captures every byte immediately, only the UI-visible progress is coalesced.
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
            lastProgressEmitMs = now
            _syncState.value = SyncState.ReceivingData(receiveBuffer.size())
        }
    }

    private var lastProgressEmitMs = 0L

    private fun onTransferComplete() {
        _syncState.value = SyncState.Inserting
        val csvText = receiveBuffer.toString(Charsets.US_ASCII.name())
        scope.launch {
            val result = readingsRepository.importCsv(csvText)
            // Only after the local insert has succeeded do we tell the ESP32 it can discard
            // its buffered copy — this ordering is what makes the protocol crash-safe.
            val gatt = bluetoothGatt
            if (gatt == null) {
                fail("Connection lost before CLEAR_BUFFER could be sent")
                return@launch
            }
            _syncState.value = SyncState.ClearingBuffer
            writeClearBuffer(gatt)
            _syncState.value = SyncState.Success(result.readings.size, result.skippedRowCount)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableDataNotifications(gatt: BluetoothGatt) {
        val dataChar = dataCharacteristic ?: return fail("Data characteristic missing")
        val enabled = gatt.setCharacteristicNotification(dataChar, true)
        if (!enabled) {
            fail("Failed to enable Data notifications")
            return
        }
        val cccd = dataChar.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (cccd == null) {
            // No CCCD present (unusual); fall back to writing SET_TIME directly.
            writeSetTime(gatt)
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

    private enum class ControlWrite { SET_TIME, REQUEST_DATA, CLEAR_BUFFER, DEVICE_SETTING }

    private var lastControlWrite: ControlWrite? = null

    @SuppressLint("MissingPermission")
    private fun writeSetTime(gatt: BluetoothGatt) {
        val nowEpochSec = System.currentTimeMillis() / 1000L
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(BleConstants.OPCODE_SET_TIME)
            .putLong(nowEpochSec)
            .array()
        lastControlWrite = ControlWrite.SET_TIME
        writeControl(gatt, payload)
    }

    @SuppressLint("MissingPermission")
    private fun writeRequestData(gatt: BluetoothGatt) {
        _syncState.value = SyncState.RequestingData
        lastControlWrite = ControlWrite.REQUEST_DATA
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_REQUEST_DATA))
    }

    @SuppressLint("MissingPermission")
    private fun writeClearBuffer(gatt: BluetoothGatt) {
        lastControlWrite = ControlWrite.CLEAR_BUFFER
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_CLEAR_BUFFER))
    }

    /** Writes SET_TEST_MODE. Device-section UI in Settings calls this while connected. */
    fun writeTestMode(enabled: Boolean) {
        val gatt = bluetoothGatt ?: return
        lastControlWrite = ControlWrite.DEVICE_SETTING
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_SET_TEST_MODE, if (enabled) 0x01 else 0x00))
        _testModeEnabled.value = enabled
    }

    /** Writes SET_WIFI_CREDENTIALS: `[ssidLen][ssid][passLen][pass]`, both ASCII/UTF-8. */
    fun writeWifiCredentials(ssid: String, password: String) {
        val gatt = bluetoothGatt ?: return
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        require(ssidBytes.size <= 255) { "SSID too long" }
        require(passBytes.size <= 255) { "Password too long" }
        val payload = ByteArray(1 + 1 + ssidBytes.size + 1 + passBytes.size)
        var i = 0
        payload[i++] = BleConstants.OPCODE_SET_WIFI_CREDENTIALS
        payload[i++] = ssidBytes.size.toByte()
        ssidBytes.copyInto(payload, i); i += ssidBytes.size
        payload[i++] = passBytes.size.toByte()
        passBytes.copyInto(payload, i)
        lastControlWrite = ControlWrite.DEVICE_SETTING
        writeControl(gatt, payload)
    }

    /** Writes ENTER_OTA_MODE (no payload). */
    fun enterOtaMode() {
        val gatt = bluetoothGatt ?: return
        lastControlWrite = ControlWrite.DEVICE_SETTING
        writeControl(gatt, byteArrayOf(BleConstants.OPCODE_ENTER_OTA_MODE))
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeControl(gatt: BluetoothGatt, payload: ByteArray) {
        val characteristic = controlCharacteristic ?: return fail("Control characteristic missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun armTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(SYNC_TIMEOUT_MS)
            fail("Sync timed out")
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun fail(message: String) {
        cancelTimeout()
        _syncState.value = SyncState.Failed(message)
        disconnect()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        controlCharacteristic = null
        dataCharacteristic = null
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 30_000L
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
    }
}
