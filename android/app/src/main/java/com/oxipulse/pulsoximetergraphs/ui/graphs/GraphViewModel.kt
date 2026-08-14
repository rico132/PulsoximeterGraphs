package com.oxipulse.pulsoximetergraphs.ui.graphs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val EMPTY_STATS = ReadingStats(
    minSpo2 = null, maxSpo2 = null, avgSpo2 = null,
    minPulse = null, maxPulse = null, avgPulse = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModel(
    private val readingsRepository: ReadingsRepository,
    thresholdsRepository: ThresholdsRepository,
    val bleGattClient: BleGattClient,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(defaultRange())
    val selectedRange: StateFlow<ClosedRange<Instant>> = _selectedRange.asStateFlow()

    // Every range change (manual date/time picks and drag-to-zoom alike) pushes the range it
    // replaced here first, so a single "zoom out" action can undo either kind of change — the
    // two are otherwise indistinguishable to the user. Capped defensively; a real session won't
    // come close before the SharingStarted timeout would tear this ViewModel down anyway.
    private val rangeHistory = ArrayDeque<ClosedRange<Instant>>()
    private val _canZoomOut = MutableStateFlow(false)
    val canZoomOut: StateFlow<Boolean> = _canZoomOut.asStateFlow()

    val thresholdConfig: StateFlow<ThresholdConfig> = thresholdsRepository.config

    val readings: StateFlow<List<ReadingEntity>> = selectedRange
        .flatMapLatest { range -> readingsRepository.observeRange(range) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recomputed via the DAO's SQL aggregate query whenever the range or underlying data changes. */
    val stats: StateFlow<ReadingStats> = combine(selectedRange, readings) { range, _ -> range }
        .map { range -> readingsRepository.statsForRange(range) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_STATS)

    val syncState: StateFlow<BleGattClient.SyncState> = bleGattClient.syncState

    fun setRange(range: ClosedRange<Instant>) {
        rangeHistory.addLast(_selectedRange.value)
        if (rangeHistory.size > MAX_RANGE_HISTORY) rangeHistory.removeFirst()
        _selectedRange.value = range
        _canZoomOut.value = true
    }

    /** Undoes the last [setRange] call (manual pick or drag-to-zoom), restoring the range it replaced. */
    fun zoomOut() {
        val previous = rangeHistory.removeLastOrNull() ?: return
        _selectedRange.value = previous
        _canZoomOut.value = rangeHistory.isNotEmpty()
    }

    /**
     * Narrows the current range to [ZOOM_IN_FACTOR] of its span, centered on its midpoint —
     * the button-driven counterpart to drag-to-zoom (which narrows to an arbitrary dragged
     * span instead). Goes through [setRange] like every other range change, so it's undoable
     * with [zoomOut] the same way a drag-to-zoom is. A no-op once the range is already at or
     * below [MIN_ZOOM_SPAN_SECONDS], so repeated taps can't zoom into an unusably tiny or empty
     * window.
     */
    fun zoomIn() {
        val current = _selectedRange.value
        val currentSpanSeconds = current.endInclusive.epochSecond - current.start.epochSecond
        if (currentSpanSeconds <= MIN_ZOOM_SPAN_SECONDS) return
        val newSpanSeconds = (currentSpanSeconds * ZOOM_IN_FACTOR)
            .toLong()
            .coerceAtLeast(MIN_ZOOM_SPAN_SECONDS)
        val centerEpochSecond = current.start.epochSecond + currentSpanSeconds / 2
        val newStart = Instant.ofEpochSecond(centerEpochSecond - newSpanSeconds / 2)
        val newEnd = Instant.ofEpochSecond(centerEpochSecond + newSpanSeconds / 2)
        setRange(newStart..newEnd)
    }

    fun importCsvText(text: String, onResult: (inserted: Int, skipped: Int) -> Unit) {
        viewModelScope.launch {
            val result = readingsRepository.importCsv(text)
            onResult(result.readings.size, result.skippedRowCount)
        }
    }

    fun startBleSync() {
        bleGattClient.startSync()
    }

    fun resetSyncState() {
        bleGattClient.resetState()
    }

    fun cancelSync() {
        bleGattClient.cancelSync()
    }

    companion object {
        private const val MAX_RANGE_HISTORY = 50
        private const val ZOOM_IN_FACTOR = 0.5
        private const val MIN_ZOOM_SPAN_SECONDS = 60L

        // Both ends are rounded independently to the *local wall-clock* hour (not truncated as
        // a raw Instant, which would snap to UTC hour boundaries and land on the wrong minute in
        // any zone with a non-whole-hour UTC offset) — floor the start, ceil the end — rather
        // than deriving one from the other. E.g. opening at 8:54 gives 8:00 the day before to
        // 9:00 today: a plain "end minus 24h" would instead put the start at 9:00 the day
        // before, an hour later than the floor of "24h ago" actually is.
        private fun defaultRange(): ClosedRange<Instant> {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val dayAgo = now.minusHours(24)
            val start = dayAgo.truncatedTo(ChronoUnit.HOURS)
            val flooredNow = now.truncatedTo(ChronoUnit.HOURS)
            val end = if (flooredNow == now) flooredNow else flooredNow.plusHours(1)
            return start.toInstant()..end.toInstant()
        }

        fun factory(
            readingsRepository: ReadingsRepository,
            thresholdsRepository: ThresholdsRepository,
            bleGattClient: BleGattClient,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GraphViewModel(readingsRepository, thresholdsRepository, bleGattClient) as T
            }
        }
    }
}
