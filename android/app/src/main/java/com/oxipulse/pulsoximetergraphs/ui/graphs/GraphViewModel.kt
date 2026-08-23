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
import kotlin.math.ceil
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
    minSpo2 = null, maxSpo2 = null, avgSpo2 = null, p95Spo2 = null, spo2EventCount = null,
    minPulse = null, maxPulse = null, avgPulse = null, p95Pulse = null,
)

/**
 * Nearest-rank 95th percentile (not interpolated, so the result is always one of the actual
 * readings — consistent with min/max, which are likewise real observed values). Computed here in
 * Kotlin from the same in-memory list already loaded for the chart, rather than via SQL (see
 * ReadingStats's own doc for why).
 */
private fun percentile95(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.size - 1)
    return sorted[index]
}

/** A desaturation "event" is defined against this SpO2 percentage — see [countSpo2Events]. */
const val SPO2_EVENT_THRESHOLD_PERCENT = 94

/**
 * Two dips below [SPO2_EVENT_THRESHOLD_PERCENT] separated by a recovery shorter than this count
 * as one continuous event, not two — see [countSpo2Events]. A brief single-sample bounce back
 * above threshold (sensor noise, a momentary good reading mid-desaturation) shouldn't fragment
 * one real episode into several.
 */
internal const val EVENT_MERGE_GAP_SECONDS = 5L

/**
 * Number of separate desaturation events: maximal runs of readings with SpO2 below
 * [SPO2_EVENT_THRESHOLD_PERCENT], where a recovery above threshold shorter than
 * [EVENT_MERGE_GAP_SECONDS] doesn't end the event — the next dip merges into the same one instead
 * of starting a new one. E.g. SpO2 dipping to 90, recovering to 96 for 2 seconds, then dipping to
 * 92 again is 1 event; the same recovery lasting 10 seconds would be 2. [readings] must already be
 * in chronological order (see ReadingDao's `ORDER BY timestampEpochSec ASC`) — unlike
 * [percentile95], this genuinely depends on sequence (and now timing), not just the multiset of
 * SpO2 values. Null (not 0) when there's no data at all, consistent with every other field in
 * [ReadingStats].
 */
// internal, not private, so GraphViewModelEventsTest can exercise this directly — the merge-gap
// timing logic is exactly the kind of thing worth a real unit test rather than trusting by eye.
internal fun countSpo2Events(readings: List<ReadingEntity>): Int? {
    if (readings.isEmpty()) return null
    var eventCount = 0
    var inEvent = false
    // Epoch second of the most recent below-threshold reading seen so far — once a run ends,
    // this is left holding that run's own last (i.e. most recent) below-threshold timestamp,
    // which is exactly what the next run's gap needs to be measured from.
    var lastBelowThresholdEpochSec: Long? = null
    for (reading in readings) {
        val belowThreshold = reading.spo2 < SPO2_EVENT_THRESHOLD_PERCENT
        if (belowThreshold) {
            if (!inEvent) {
                val gapSeconds = lastBelowThresholdEpochSec?.let { reading.timestampEpochSec - it }
                if (gapSeconds == null || gapSeconds >= EVENT_MERGE_GAP_SECONDS) {
                    eventCount++
                }
                inEvent = true
            }
            lastBelowThresholdEpochSec = reading.timestampEpochSec
        } else {
            inEvent = false
        }
    }
    return eventCount
}

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

    /**
     * min/max/avg come from the DAO's SQL aggregate query; p95Spo2/p95Pulse/spo2EventCount are
     * computed in Kotlin from [readings] itself (the same, already-loaded rows for this exact
     * range) rather than by the query, since percentile support isn't reliably available across
     * the SQLite versions this app's supported Android versions ship with, and event counting
     * needs sequence order anyway — see ReadingStats's own doc.
     */
    val stats: StateFlow<ReadingStats> = combine(selectedRange, readings) { range, currentReadings ->
        range to currentReadings
    }
        .map { (range, currentReadings) ->
            readingsRepository.statsForRange(range).copy(
                p95Spo2 = percentile95(currentReadings.map { it.spo2 }),
                p95Pulse = percentile95(currentReadings.map { it.pulse }),
                spo2EventCount = countSpo2Events(currentReadings),
            )
        }
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
