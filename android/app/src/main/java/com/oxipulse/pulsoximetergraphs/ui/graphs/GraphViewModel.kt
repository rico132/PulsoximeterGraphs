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
    minSpo2 = null, maxSpo2 = null, avgSpo2 = null, p95Spo2 = null, spo2EventCount = null,
    minPulse = null, maxPulse = null, avgPulse = null, p95Pulse = null,
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

    /**
     * A lightweight `COUNT(*)` for the selected range, re-emitted by Room's own invalidation
     * tracking whenever the `readings` table changes (a BLE sync, a CSV import) — shared as the
     * "something changed, refetch" trigger for both [readings] and [stats] below, instead of each
     * keeping its own `Flow<List<ReadingEntity>>` over the (potentially huge) range. See
     * [ReadingsRepository]'s own doc for the full reasoning.
     */
    private val rangeRowCount: StateFlow<Int> = selectedRange
        .flatMapLatest { range -> readingsRepository.observeCountInRange(range) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Bounded to at most a few hundred points regardless of how wide [selectedRange] is or how
     * many raw readings it contains — see [ReadingsRepository.plottedReadings]'s own doc. This is
     * charting data specifically (decimated, extremes-preserving), not a faithful copy of every
     * reading in range; [stats] below is computed independently, from the *entire* range, for
     * exactly that reason.
     */
    val readings: StateFlow<List<ReadingEntity>> = combine(
        selectedRange,
        rangeRowCount,
    ) { range, count -> range to count }
        .map { (range, count) -> readingsRepository.plottedReadings(range, count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Unlike [readings] above, every field here reflects the *entire* selected range, however
     * wide — see [ReadingsRepository.statsForRange]'s own doc for how it stays bounded in memory
     * while remaining exact (not sampled/approximated) even across a multi-year range.
     * [thresholdConfig] is combined in too so that editing spo2EventThreshold in Settings
     * recomputes spo2EventCount/spo2EventsPerHour immediately, the same way it already recomputes
     * the chart's threshold bands.
     */
    val stats: StateFlow<ReadingStats> = combine(
        selectedRange,
        rangeRowCount,
        thresholdConfig,
    ) { range, _, config -> range to config }
        .map { (range, config) -> readingsRepository.statsForRange(range, config.spo2EventThreshold) }
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
