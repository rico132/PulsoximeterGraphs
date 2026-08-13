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
        _selectedRange.value = range
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

    companion object {
        private fun defaultRange(): ClosedRange<Instant> {
            val end = Instant.now()
            val start = end.minus(24, ChronoUnit.HOURS)
            return start..end
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
