package com.oxipulse.pulsoximetergraphs.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdsRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val thresholdsRepository: ThresholdsRepository,
    val bleGattClient: BleGattClient,
) : ViewModel() {

    val config: StateFlow<ThresholdConfig> = thresholdsRepository.config
    val testModeEnabled: StateFlow<Boolean?> = bleGattClient.testModeEnabled
    val syncState: StateFlow<BleGattClient.SyncState> = bleGattClient.syncState

    /** Returns null on success, or a validation-error message. Never persists an invalid config. */
    fun save(newConfig: ThresholdConfig): String? = thresholdsRepository.update(newConfig)

    fun setTestMode(enabled: Boolean) = bleGattClient.writeTestMode(enabled)

    fun setWifiCredentials(ssid: String, password: String) =
        bleGattClient.writeWifiCredentials(ssid, password)

    fun enterOtaMode() = bleGattClient.enterOtaMode()

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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(thresholdsRepository, bleGattClient) as T
        }
    }
}
