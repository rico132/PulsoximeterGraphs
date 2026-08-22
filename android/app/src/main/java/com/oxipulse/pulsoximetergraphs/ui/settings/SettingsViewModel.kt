package com.oxipulse.pulsoximetergraphs.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oxipulse.pulsoximetergraphs.data.ble.BleDebugLog
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val thresholdsRepository: ThresholdsRepository,
    val bleGattClient: BleGattClient,
    private val readingsRepository: ReadingsRepository,
) : ViewModel() {

    val config: StateFlow<ThresholdConfig> = thresholdsRepository.config
    val testModeEnabled: StateFlow<Boolean?> = bleGattClient.testModeEnabled
    val syncState: StateFlow<BleGattClient.SyncState> = bleGattClient.syncState
    val debugLog: StateFlow<String> = BleDebugLog.entries

    fun clearDebugLog() = BleDebugLog.clear()

    /** Moved here from GraphViewModel along with the Import tab's UI (see SettingsScreen). */
    fun importCsvText(text: String, onResult: (inserted: Int, skipped: Int) -> Unit) {
        viewModelScope.launch {
            val result = readingsRepository.importCsv(text)
            onResult(result.readings.size, result.skippedRowCount)
        }
    }

    /** Returns null on success, or a validation-error message. Never persists an invalid config. */
    fun save(newConfig: ThresholdConfig): String? = thresholdsRepository.update(newConfig)

    fun setTestMode(enabled: Boolean) = bleGattClient.writeTestMode(enabled)

    fun setWifiCredentials(ssid: String, password: String) =
        bleGattClient.writeWifiCredentials(ssid, password)

    fun enterOtaMode() = bleGattClient.enterOtaMode()

    /** See [BleGattClient.resyncFromDevice] -- recovers after this app's own local data is lost. */
    fun resyncFromDevice() = bleGattClient.resyncFromDevice()

    fun isDeviceConnected(): Boolean = when (syncState.value) {
        is BleGattClient.SyncState.Success,
        is BleGattClient.SyncState.ReceivingData,
        is BleGattClient.SyncState.RequestingData,
        is BleGattClient.SyncState.Inserting,
        is BleGattClient.SyncState.ClearingBuffer,
        -> true
        else -> false
    }

    companion object {
        fun factory(
            thresholdsRepository: ThresholdsRepository,
            bleGattClient: BleGattClient,
            readingsRepository: ReadingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(thresholdsRepository, bleGattClient, readingsRepository) as T
        }
    }
}
