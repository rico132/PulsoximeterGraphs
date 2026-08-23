package com.oxipulse.pulsoximetergraphs.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oxipulse.pulsoximetergraphs.data.ble.BleDebugLog
import com.oxipulse.pulsoximetergraphs.data.ble.BleFirmwareUpdateClient
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdsRepository
import com.oxipulse.pulsoximetergraphs.data.update.GithubReleaseChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val thresholdsRepository: ThresholdsRepository,
    val bleGattClient: BleGattClient,
    val bleFirmwareUpdateClient: BleFirmwareUpdateClient,
    private val readingsRepository: ReadingsRepository,
) : ViewModel() {

    val config: StateFlow<ThresholdConfig> = thresholdsRepository.config
    val testModeEnabled: StateFlow<Boolean?> = bleGattClient.testModeEnabled
    val syncState: StateFlow<BleGattClient.SyncState> = bleGattClient.syncState
    val debugLog: StateFlow<String> = BleDebugLog.entries

    val firmwareUpdateState: StateFlow<BleFirmwareUpdateClient.UpdateState> =
        bleFirmwareUpdateClient.updateState
    val deviceVersionCheckState: StateFlow<BleFirmwareUpdateClient.VersionCheckState> =
        bleFirmwareUpdateClient.versionCheckState

    sealed interface FirmwareCheckState {
        data object Idle : FirmwareCheckState
        data object Checking : FirmwareCheckState
        data class UpdateAvailable(
            val release: GithubReleaseChecker.FirmwareRelease,
            val currentVersion: String?,
        ) : FirmwareCheckState
        data class UpToDate(val version: String) : FirmwareCheckState
        data class Error(val message: String) : FirmwareCheckState
    }

    private val _firmwareCheckState = MutableStateFlow<FirmwareCheckState>(FirmwareCheckState.Idle)
    val firmwareCheckState: StateFlow<FirmwareCheckState> = _firmwareCheckState.asStateFlow()

    /** Cached across the check so [startFirmwareUpdate] doesn't need to re-download the asset. */
    private var pendingFirmwareBytes: ByteArray? = null
    private var pendingFirmwareMd5Hex: String? = null

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

    fun isDeviceConnected(): Boolean = when (syncState.value) {
        is BleGattClient.SyncState.Success,
        is BleGattClient.SyncState.ReceivingData,
        is BleGattClient.SyncState.RequestingData,
        is BleGattClient.SyncState.Inserting,
        is BleGattClient.SyncState.ClearingBuffer,
        -> true
        else -> false
    }

    /**
     * Checks the latest GitHub release for a firmware asset, and — best-effort, only if the
     * ESP32 is currently reachable over BLE — reads its currently-running version to decide
     * whether that release is actually newer. A device that can't be reached right now (out of
     * range, powered off) doesn't block the check: the release is still offered, just without a
     * "you already have this" comparison, since the whole point of the update flow is to be
     * usable without already knowing the device's state.
     */
    fun checkForFirmwareUpdate() {
        if (_firmwareCheckState.value == FirmwareCheckState.Checking) return
        _firmwareCheckState.value = FirmwareCheckState.Checking
        pendingFirmwareBytes = null
        pendingFirmwareMd5Hex = null
        viewModelScope.launch {
            when (val result = GithubReleaseChecker.checkForUpdate()) {
                is GithubReleaseChecker.CheckResult.Error -> {
                    _firmwareCheckState.value = FirmwareCheckState.Error(result.message)
                }
                GithubReleaseChecker.CheckResult.NoFirmwareAsset -> {
                    _firmwareCheckState.value =
                        FirmwareCheckState.Error("Latest release has no firmware asset")
                }
                is GithubReleaseChecker.CheckResult.Available -> {
                    val currentVersion = readDeviceVersionOrNull()
                    _firmwareCheckState.value = if (currentVersion == result.release.version) {
                        FirmwareCheckState.UpToDate(currentVersion)
                    } else {
                        FirmwareCheckState.UpdateAvailable(result.release, currentVersion)
                    }
                }
            }
        }
    }

    /**
     * Suspends until [BleFirmwareUpdateClient.checkDeviceVersion]'s connection attempt has
     * concluded one way or the other, returning null on any failure (device unreachable, not
     * bonded yet, etc.) rather than propagating it — see [checkForFirmwareUpdate]'s own doc for
     * why that's treated as "unknown," not a hard error for the whole check.
     */
    private suspend fun readDeviceVersionOrNull(): String? {
        bleFirmwareUpdateClient.resetState()
        bleFirmwareUpdateClient.checkDeviceVersion()
        val state = bleFirmwareUpdateClient.versionCheckState.first {
            it !is BleFirmwareUpdateClient.VersionCheckState.Idle &&
                it !is BleFirmwareUpdateClient.VersionCheckState.Checking
        }
        return (state as? BleFirmwareUpdateClient.VersionCheckState.Checked)?.version
    }

    /** Downloads the checked release's firmware asset, then hands it to [startFirmwareUpdate]. */
    fun downloadAndStartFirmwareUpdate(release: GithubReleaseChecker.FirmwareRelease) {
        viewModelScope.launch {
            when (val result = GithubReleaseChecker.downloadFirmware(release)) {
                is GithubReleaseChecker.DownloadResult.Error -> {
                    _firmwareCheckState.value = FirmwareCheckState.Error(
                        "Download failed: ${result.message}",
                    )
                }
                is GithubReleaseChecker.DownloadResult.Success -> {
                    pendingFirmwareBytes = result.bytes
                    pendingFirmwareMd5Hex = result.md5Hex
                    bleFirmwareUpdateClient.resetState()
                    bleFirmwareUpdateClient.startUpdate(result.bytes, result.md5Hex)
                }
            }
        }
    }

    fun cancelFirmwareUpdate() = bleFirmwareUpdateClient.cancelUpdate()

    companion object {
        fun factory(
            thresholdsRepository: ThresholdsRepository,
            bleGattClient: BleGattClient,
            bleFirmwareUpdateClient: BleFirmwareUpdateClient,
            readingsRepository: ReadingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    thresholdsRepository,
                    bleGattClient,
                    bleFirmwareUpdateClient,
                    readingsRepository,
                ) as T
        }
    }
}
