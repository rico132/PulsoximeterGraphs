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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
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

        /**
         * [totalBytes]/[fileIndex]/[fileCount] are only known once a multi-file header has been
         * parsed (see [MultiFileMeta]) — null for a legacy single-file transfer (real ESP32, or
         * the sender script's single-file path), where the total size is never declared upfront.
         */
        data class ReceivingData(
            val bytesReceived: Int,
            val totalBytes: Int? = null,
            val fileIndex: Int? = null,
            val fileCount: Int? = null,
        ) : SyncState

        data object Inserting : SyncState
        data object ClearingBuffer : SyncState

        /**
         * A sync failed in a way [fail] considered transient (see its `retryable` parameter) and
         * a fresh attempt is already scheduled — shown instead of briefly dropping back to [Idle]
         * (which would flash the sync dialog closed and reopened a moment later) or surfacing a
         * [Failed] the user would have to notice and manually retry. [attempt] is 1-based.
         */
        data class Retrying(val attempt: Int, val maxAttempts: Int) : SyncState

        data class Success(val rowsInserted: Int, val rowsSkipped: Int) : SyncState
        data class Failed(val message: String) : SyncState
    }

    /**
     * Parsed multi-file header (see [BleConstants.MULTI_FILE_MAGIC] / PROTOCOL.md) — the byte
     * length of each file in the transfer, in order, plus how many header bytes precede the
     * actual file bytes in [receiveBuffer].
     */
    private data class MultiFileMeta(val fileLengths: List<Int>, val headerLength: Int) {
        val totalBytes: Int = fileLengths.sum()
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
    private var receiveStartMs = 0L
    private var chunkArrivalLogCount = 0
    private var lastChunkArrivalMs = 0L
    private var snapshotBytes = 0
    private var lastSnapshotMs = 0L
    private var timeoutJob: Job? = null

    // Multi-file transfer tracking (see MultiFileMeta / BleConstants.MULTI_FILE_MAGIC). Both null
    // means "not yet determined" -- resolved to one or the other as soon as the first byte (or,
    // for the header case, the first few bytes) of a transfer arrives.
    private var multiFileMeta: MultiFileMeta? = null
    private var legacyMode = false

    /**
     * Set by [cancelSync]. Checked before committing anything to the database so a cancelled
     * sync never saves partial data -- and in [onConnectionStateChange] so the disconnect that
     * cancelling itself triggers doesn't get misreported as a connection failure.
     */
    private var cancelled = false

    // Both reset to a fresh state only by a genuinely new, externally-triggered startSync() call
    // (isRetry = false below) -- an internal retry's own startSync(isRetry = true) call must NOT
    // reset retryCount back to 0, or MAX_SYNC_RETRIES would never actually be reached.
    private var retryCount = 0
    private var retryJob: Job? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanCallback: ScanCallback? = null

    /** Kicks off a full sync: scan -> connect -> SET_TIME -> REQUEST_DATA -> insert -> CLEAR_BUFFER. */
    @SuppressLint("MissingPermission") // Callers must check BlePermissions before calling.
    fun startSync() = startSync(isRetry = false)

    /**
     * [isRetry] is true only when [fail]'s own retry scheduling calls this after a delay -- that
     * path deliberately bypasses the "already in progress" guard below (by construction, it only
     * ever runs after the previous attempt has already fully failed and disconnected, so there is
     * nothing for it to race against) and preserves [retryCount] across the call instead of
     * resetting it, which is what actually makes [MAX_SYNC_RETRIES] a real cap rather than an
     * infinite retry loop.
     */
    @SuppressLint("MissingPermission")
    private fun startSync(isRetry: Boolean) {
        if (!isRetry) {
            if (_syncState.value !is SyncState.Idle &&
                _syncState.value !is SyncState.Success &&
                _syncState.value !is SyncState.Failed
            ) {
                return // A sync is already in progress.
            }
            retryCount = 0
        }

        log(if (isRetry) "Retry attempt $retryCount/$MAX_SYNC_RETRIES starting" else "Sync started")
        receiveBuffer.reset()
        lastProgressEmitMs = 0L
        chunkArrivalLogCount = 0
        lastChunkArrivalMs = 0L
        multiFileMeta = null
        legacyMode = false
        cancelled = false
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
        log("Found device, connecting to $address")
        _syncState.value = SyncState.Connecting
        val adapter = bluetoothAdapter ?: return fail("Bluetooth adapter unavailable")
        val device = adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "CODED"
        else -> "unknown($phy)"
    }

    /** Logs to logcat and mirrors into [BleDebugLog] so a sync can be diagnosed without adb. */
    private fun log(message: String) {
        Log.d(TAG, message)
        BleDebugLog.add(message)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected (status $status), requesting priority + PHY")
                // Without this, Android negotiates its default "balanced" connection interval
                // (tens of ms per connection event), which caps real throughput far below what
                // the negotiated MTU/chunk size would allow — raising MTU alone doesn't touch
                // this ceiling. Requesting HIGH here (shortest interval Android permits) is what
                // actually speeds up the transfer; it must happen as early as possible since the
                // renegotiation itself takes a moment to take effect.
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // 1M PHY is ~1Mbps raw air rate; 2M PHY roughly doubles it. Falls back silently
                // to 1M if either side (this phone's adapter or the peer's) doesn't support it —
                // there's no separate capability check needed before asking.
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                // Cancelling calls disconnect() itself, so the resulting STATE_DISCONNECTED here
                // must not get reinterpreted as an unexpected failure on top of the cancel. Same
                // reasoning for Retrying: fail()'s own retry path calls disconnect() too, and on
                // some devices that still asynchronously delivers a STATE_DISCONNECTED here some
                // time later -- by then the state is already Retrying (fail() sets it before
                // calling disconnect(), same ordering as the plain-Failed path), so without this
                // check a single real failure would schedule two overlapping retries instead of
                // one.
                if (!cancelled &&
                    _syncState.value !is SyncState.Success &&
                    _syncState.value !is SyncState.Failed &&
                    _syncState.value !is SyncState.Retrying
                ) {
                    // A mid-transfer disconnect is exactly the kind of transient BLE hiccup a
                    // retry can plausibly recover from — see fail()'s own doc on `retryable`.
                    fail("Device disconnected before sync completed", retryable = true)
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
            log("Services discovered, requesting MTU ${BleConstants.REQUESTED_MTU}")
            armTimeout()
            gatt.requestMtu(BleConstants.REQUESTED_MTU)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Whether or not the negotiation succeeded, proceed with whatever MTU we have —
            // the protocol must also work correctly at the default, un-negotiated MTU of 23.
            log("MTU negotiated: $mtu (status $status)")
            // Confirmed from real logs: the requestConnectionPriority() call in
            // onConnectionStateChange, fired the instant the link comes up, is unreliable —
            // back-to-back runs of the identical file landed at both ~15ms and ~80ms per
            // notification despite requesting HIGH priority every time, and there's no
            // completion callback for that call to detect which happened. Re-requesting it here,
            // ~1s into the connection after service discovery and MTU exchange have already
            // completed, gives the link a second chance to actually apply it once it's had time
            // to settle — repeating a request that already succeeded is a harmless no-op.
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            armTimeout()
            enableDataNotifications(gatt)
        }

        // The actual negotiated connection interval (the real throughput lever behind
        // requestConnectionPriority — see onConnectionStateChange) has no public read API at
        // all; onConnectionUpdated exists but is a hidden/system callback, not part of the SDK
        // (confirmed against the platform's android.jar — it doesn't override anything here).
        // PHY is the one comparable lever Android *does* expose a public result callback for.
        @SuppressLint("MissingPermission")
        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            log("PHY updated: tx=${phyName(txPhy)} rx=${phyName(rxPhy)} (status $status)")
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
        // Ignore anything arriving once this transfer has already been fully handled (or hasn't
        // actually been requested) — BleGattServer now deliberately resends its terminator a few
        // times (see its own comment) since a single GATT notification has no delivery guarantee,
        // so a stray extra one reaching the phone after the first is expected, not a bug. Without
        // this guard it would re-run onTransferComplete() a second time: harmless at the DB level
        // (ReadingDao inserts REPLACE-on-conflict, keyed by timestamp) but would re-parse the
        // whole CSV, send a redundant second CLEAR_BUFFER, and re-emit a duplicate Success state
        // (and the snackbar it triggers) for a transfer the user already saw complete.
        val state = _syncState.value
        if (state !is SyncState.RequestingData && state !is SyncState.ReceivingData) return

        // Every chunk received is forward progress, so the watchdog resets here too — a large
        // CSV sent in many small notifications must not time out purely because the transfer as
        // a whole runs past SYNC_TIMEOUT_MS, only if it actually stalls.
        armTimeout()
        if (value.size == 1 && value[0] == BleConstants.DATA_TERMINATOR[0]) {
            onTransferComplete()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // There's no public Android API to read the actual negotiated connection interval (see
        // onPhyUpdate's comment for the PHY equivalent) — requestConnectionPriority() is only a
        // request, and whether the peer actually granted a short interval is otherwise invisible.
        // But the interval controls how often a notification can go out at all, so timing raw
        // chunk arrivals directly reveals it: a steady multi-ms gap between chunks *is* the
        // interval, no HCI snoop needed. Logged unthrottled (unlike the progress state below) for
        // only the first few chunks of each transfer, since that's all that's needed to see the
        // pattern and this must stay off for the rest of a large transfer to avoid reintroducing
        // the same per-chunk main-thread cost the progress throttle above exists to avoid.
        if (chunkArrivalLogCount < CHUNK_ARRIVAL_LOG_LIMIT) {
            val gap = if (chunkArrivalLogCount == 0) 0L else now - lastChunkArrivalMs
            lastChunkArrivalMs = now
            chunkArrivalLogCount++
            log("Chunk #$chunkArrivalLogCount: ${value.size} bytes, +${gap}ms since previous chunk")
        }
        receiveBuffer.write(value)
        if (multiFileMeta == null && !legacyMode) tryParseMultiFileHeader()
        // Two known-slow and known-fast runs of the *same* file showed wildly different overall
        // throughput (7.9 KB/s vs 32.4 KB/s) despite identical negotiated MTU/PHY -- but the
        // per-chunk log above only samples the first 20 chunks, so it can't tell a transfer that's
        // slow from the start apart from one that starts fine and degrades partway through (a
        // known real-world pattern: BLE/Wi-Fi radio coexistence throttling can kick in once
        // there's been enough airtime, not just at connection time). A periodic snapshot across
        // the *whole* transfer answers that with only a couple dozen extra log lines even on a
        // 90-second transfer, instead of re-logging every chunk for its full duration.
        if (now - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            val bytesSinceSnapshot = receiveBuffer.size() - snapshotBytes
            val elapsed = (now - lastSnapshotMs).coerceAtLeast(1)
            log(
                "Snapshot: ${receiveBuffer.size()} bytes total, " +
                    "${"%.1f".format(bytesSinceSnapshot / elapsed.toDouble())} KB/s in the last ${elapsed}ms",
            )
            snapshotBytes = receiveBuffer.size()
            lastSnapshotMs = now
        }
        // GATT callbacks are delivered on the main thread (no Handler/Executor was passed to
        // connectGatt), which is also where Compose recomposes. ReceivingData is a data class
        // keyed on the byte count, so emitting it on every single notification forced a full
        // recomposition (+ a LaunchedEffect(syncState) relaunch in GraphScreen) per chunk — with
        // a fast link, that main-thread work becomes the actual bottleneck: the UI can't keep up
        // with notification arrival, so the app lags behind the radio instead of the other way
        // around. Throttle to ~10 updates/sec, same as the sender script already throttles its
        // own progress print (see ble_csv_sender.py's send_csv) — the buffer itself still
        // captures every byte immediately, only the UI-visible progress is coalesced.
        if (now - lastProgressEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
            lastProgressEmitMs = now
            _syncState.value = receivingDataState()
        }
    }

    private var lastProgressEmitMs = 0L

    /**
     * Attempts to parse a multi-file header (see [MultiFileMeta] / [BleConstants.MULTI_FILE_MAGIC])
     * from the start of [receiveBuffer]. Determines [legacyMode] immediately from the very first
     * byte alone (no need to wait for more data), but the header itself — `fileCount`, then
     * `4 * fileCount` bytes of per-file lengths — may legitimately arrive split across more than
     * one notification if the sender's chunk size is unusually small, so this just waits for
     * more bytes (leaving both [multiFileMeta] and [legacyMode] unset) until enough are buffered.
     */
    private fun tryParseMultiFileHeader() {
        val buffered = receiveBuffer.size()
        if (buffered < 1) return
        val bytes = receiveBuffer.toByteArray()
        if (bytes[0] != BleConstants.MULTI_FILE_MAGIC) {
            legacyMode = true
            return
        }
        if (buffered < 2) return
        val fileCount = bytes[1].toInt() and 0xFF
        val headerLength = 2 + 4 * fileCount
        if (buffered < headerLength) return
        val fileLengths = (0 until fileCount).map { i ->
            val offset = 2 + i * 4
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        multiFileMeta = MultiFileMeta(fileLengths, headerLength)
        log("Multi-file header parsed: $fileCount file(s), ${fileLengths.sum()} total bytes")
    }

    /** Current [SyncState.ReceivingData], with multi-file progress once [multiFileMeta] is known. */
    private fun receivingDataState(): SyncState.ReceivingData {
        val meta = multiFileMeta ?: return SyncState.ReceivingData(receiveBuffer.size())
        val dataBytesReceived = (receiveBuffer.size() - meta.headerLength).coerceAtLeast(0)
        var cumulative = 0
        var fileIndex = meta.fileLengths.size
        for ((i, length) in meta.fileLengths.withIndex()) {
            cumulative += length
            if (dataBytesReceived < cumulative) {
                fileIndex = i + 1
                break
            }
        }
        return SyncState.ReceivingData(
            bytesReceived = dataBytesReceived,
            totalBytes = meta.totalBytes,
            fileIndex = fileIndex,
            fileCount = meta.fileLengths.size,
        )
    }

    private fun onTransferComplete() {
        val elapsedMs = SystemClock.elapsedRealtime() - receiveStartMs
        val bytes = receiveBuffer.size()
        val kbPerSec = if (elapsedMs > 0) bytes / elapsedMs.toDouble() else 0.0
        log("Transfer complete: $bytes bytes in ${elapsedMs}ms (${"%.1f".format(kbPerSec)} KB/s)")
        if (cancelled) return // Sync was cancelled after the terminator but before this callback ran.
        _syncState.value = SyncState.Inserting
        val meta = multiFileMeta
        val allBytes = receiveBuffer.toByteArray()
        // One CSV text segment per file — a legacy (non-multi-file) transfer is just the whole
        // buffer as a single segment, same as before this feature existed.
        val csvSegments = if (meta != null) {
            var offset = meta.headerLength
            meta.fileLengths.map { length ->
                val text = String(allBytes, offset, length, Charsets.US_ASCII)
                offset += length
                text
            }
        } else {
            listOf(String(allBytes, Charsets.US_ASCII))
        }
        scope.launch {
            val results = csvSegments.map { readingsRepository.importCsv(it) }
            if (cancelled) return@launch // Cancelled while the (off-thread) parse+insert ran.
            // Only after the local insert has succeeded do we tell the ESP32 it can discard
            // its buffered copy — this ordering is what makes the protocol crash-safe.
            val gatt = bluetoothGatt
            if (gatt == null) {
                fail("Connection lost before CLEAR_BUFFER could be sent")
                return@launch
            }
            _syncState.value = SyncState.ClearingBuffer
            writeClearBuffer(gatt)
            val rowsInserted = results.sumOf { it.readings.size }
            val rowsSkipped = results.sumOf { it.skippedRowCount }
            log("Sync succeeded: $rowsInserted rows inserted, $rowsSkipped skipped across ${csvSegments.size} file(s)")
            _syncState.value = SyncState.Success(rowsInserted, rowsSkipped)
        }
    }

    /**
     * Aborts an in-progress sync and disconnects, WITHOUT saving anything: only effective before
     * the local database insert has begun (Scanning/Connecting/RequestingData/ReceivingData/
     * Retrying) — once the transfer has already completed and insertion is under way (Inserting/
     * ClearingBuffer), this is a no-op, since by then there's normally nothing left to
     * meaningfully cancel (the insert itself is a single fast batch write; see PROTOCOL.md's
     * crash-safety ordering). The UI is expected to hide/disable its Cancel affordance in those
     * states for the same reason.
     */
    fun cancelSync() {
        when (_syncState.value) {
            is SyncState.Scanning, is SyncState.Connecting,
            is SyncState.RequestingData, is SyncState.ReceivingData,
            is SyncState.Retrying,
            -> {
                log("Sync cancelled by user")
                cancelled = true
                cancelTimeout()
                // Otherwise a retry already scheduled (see fail()'s `retryable` path) would fire
                // after this cancel and silently resurrect a sync the user just backed out of.
                retryJob?.cancel()
                retryJob = null
                _syncState.value = SyncState.Idle
                disconnect()
            }
            else -> Unit
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
        receiveStartMs = SystemClock.elapsedRealtime()
        snapshotBytes = 0
        lastSnapshotMs = receiveStartMs
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
            // A stall here is exactly the failure mode a retry can plausibly recover from: BLE
            // notifications are unacknowledged, so a burst of them (including, worst case, the
            // final terminator) can go missing in transit with neither side ever finding out --
            // the ESP32 believes it sent everything and disconnects normally afterward, while the
            // phone just stops receiving anything at all. Nothing is lost on the device either
            // way (its buffer isn't cleared until a CLEAR_BUFFER this sync never got to send), so
            // simply trying the whole transfer again is safe.
            fail("Sync timed out", retryable = true)
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * [retryable]: if true and under [MAX_SYNC_RETRIES], schedules a fresh [startSync] attempt
     * after [RETRY_DELAY_MS] instead of surfacing [SyncState.Failed] immediately -- safe to do
     * unconditionally for the failure modes that pass true (see their own call sites) because
     * neither leaves anything corrupted to clean up first: the ESP32 never clears its buffer
     * until a CLEAR_BUFFER this sync never got to send, so a from-scratch retry can't lose data,
     * only re-download what's already safely still sitting on the device.
     */
    private fun fail(message: String, retryable: Boolean = false) {
        log("FAILED: $message")
        cancelTimeout()
        if (retryable && retryCount < MAX_SYNC_RETRIES) {
            retryCount++
            val attempt = retryCount
            log("Will retry in ${RETRY_DELAY_MS}ms (attempt $attempt/$MAX_SYNC_RETRIES) after: $message")
            // Set *before* disconnect(), same ordering as the plain-Failed path below: on some
            // devices disconnect()'s gatt.close() still asynchronously delivers a
            // STATE_DISCONNECTED sometime later (see onConnectionStateChange's own comment), and
            // Retrying must already be visible by then so that callback's guard doesn't treat it
            // as a second, unexpected disconnect and schedule a second retry on top of this one.
            _syncState.value = SyncState.Retrying(attempt, MAX_SYNC_RETRIES)
            disconnect()
            retryJob = scope.launch {
                delay(RETRY_DELAY_MS)
                startSync(isRetry = true)
            }
            return
        }
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
        private const val TAG = "BleGattClient"
        private const val SYNC_TIMEOUT_MS = 30_000L
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
        private const val CHUNK_ARRIVAL_LOG_LIMIT = 20
        private const val SNAPSHOT_INTERVAL_MS = 2_000L

        // See fail()'s own doc: retries are safe here because nothing is destroyed on a failed
        // attempt (the ESP32 keeps its buffer until CLEAR_BUFFER, which a failed sync never
        // sends), so this only trades a little time and battery for not making the user notice a
        // stall and manually re-tap sync themselves.
        private const val MAX_SYNC_RETRIES = 2
        // Long enough to give a transient radio hiccup (the failure mode this targets) a moment
        // to clear rather than immediately repeating into the same conditions; short enough that
        // three total attempts (this delay twice, plus the initial one) still finishes well
        // inside the time a user would wait around for a manual retry anyway.
        private const val RETRY_DELAY_MS = 2_000L
    }
}
