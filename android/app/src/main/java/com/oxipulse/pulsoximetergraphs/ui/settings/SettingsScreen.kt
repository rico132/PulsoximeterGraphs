package com.oxipulse.pulsoximetergraphs.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxipulse.pulsoximetergraphs.data.ble.BleFirmwareUpdateClient
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            appContainer.thresholdsRepository,
            appContainer.bleGattClient,
            appContainer.bleFirmwareUpdateClient,
            appContainer.readingsRepository,
        ),
    )
    val config by viewModel.config.collectAsState()
    val testModeEnabled by viewModel.testModeEnabled.collectAsState()
    val debugLog by viewModel.debugLog.collectAsState()

    // Local editable draft, seeded from the persisted config and reset whenever it changes
    // externally (e.g. after a successful save round-trips a new StateFlow value).
    var draft by remember(config) { mutableStateOf(config) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                SETTINGS_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (selectedTab) {
                    0 -> ConfigTab(
                        draft = draft,
                        onDraftChange = { draft = it },
                        errorMessage = errorMessage,
                        savedMessage = savedMessage,
                        onSave = {
                            val error = viewModel.save(draft)
                            errorMessage = error
                            savedMessage = if (error == null) "Saved" else null
                        },
                    )
                    1 -> DeviceSection(viewModel, testModeEnabled)
                    2 -> DebugLogSection(debugLog, onClear = viewModel::clearDebugLog)
                    3 -> ImportTab(viewModel)
                }
            }
        }
    }
}

private val SETTINGS_TABS = listOf("Config", "Device", "BLE Log", "Import")

@Composable
private fun ConfigTab(
    draft: ThresholdConfig,
    onDraftChange: (ThresholdConfig) -> Unit,
    errorMessage: String?,
    savedMessage: String?,
    onSave: () -> Unit,
) {
    Text("Thresholds", style = MaterialTheme.typography.titleMedium)

    ThresholdNumberField("SpO2 orange", draft.spo2Orange) { onDraftChange(draft.copy(spo2Orange = it)) }
    ThresholdNumberField("SpO2 red", draft.spo2Red) { onDraftChange(draft.copy(spo2Red = it)) }
    ThresholdNumberField("Pulse low orange", draft.pulseLowOrange) { onDraftChange(draft.copy(pulseLowOrange = it)) }
    ThresholdNumberField("Pulse low red", draft.pulseLowRed) { onDraftChange(draft.copy(pulseLowRed = it)) }
    ThresholdNumberField("Pulse high orange", draft.pulseHighOrange) { onDraftChange(draft.copy(pulseHighOrange = it)) }
    ThresholdNumberField("Pulse high red", draft.pulseHighRed) { onDraftChange(draft.copy(pulseHighRed = it)) }

    val liveError = draft.validate()
    if (liveError != null) {
        Text(liveError, color = MaterialTheme.colorScheme.error)
    }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    Button(
        onClick = onSave,
        enabled = liveError == null,
        modifier = Modifier.wrapContentSize(),
    ) {
        Text("Save")
    }
}

@Composable
private fun ThresholdNumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun DeviceSection(viewModel: SettingsViewModel, testModeEnabled: Boolean?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Test mode")
                        Text(
                            "When on, the ESP32 never deletes downloaded stored records.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = testModeEnabled ?: true,
                        onCheckedChange = { viewModel.setTestMode(it) },
                    )
                }
            }
        }

        FirmwareUpdateCard(viewModel)
    }
}

/**
 * Firmware update over BLE: checks this repo's latest GitHub release for a firmware asset,
 * downloads it, and pushes it straight to the ESP32 over the same BLE link already used for
 * syncing (see [BleFirmwareUpdateClient] and PROTOCOL.md §"BLE firmware update"). Does not
 * require [DeviceSection]'s own `connected` (an active CSV sync) — it scans for and connects to
 * the device itself, independently, the moment "Check for update" is tapped.
 */
@Composable
private fun FirmwareUpdateCard(viewModel: SettingsViewModel) {
    val checkState by viewModel.firmwareCheckState.collectAsState()
    val updateState by viewModel.firmwareUpdateState.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Firmware update (BLE)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Checks this project's latest GitHub release for new ESP32 firmware, downloads " +
                    "it, and sends it to the device over Bluetooth — no WiFi needed.",
                style = MaterialTheme.typography.bodySmall,
            )

            val uploading = updateState is BleFirmwareUpdateClient.UpdateState.Uploading ||
                updateState is BleFirmwareUpdateClient.UpdateState.Verifying ||
                updateState is BleFirmwareUpdateClient.UpdateState.Scanning ||
                updateState is BleFirmwareUpdateClient.UpdateState.Connecting

            when (val state = updateState) {
                is BleFirmwareUpdateClient.UpdateState.Uploading -> {
                    val percent = if (state.totalBytes > 0) {
                        (state.bytesSent * 100 / state.totalBytes).coerceIn(0, 100)
                    } else {
                        0
                    }
                    Text("Sending firmware… $percent% (${state.bytesSent}/${state.totalBytes} bytes)")
                }
                BleFirmwareUpdateClient.UpdateState.Verifying ->
                    Text("Verifying and switching boot partition…")
                BleFirmwareUpdateClient.UpdateState.Scanning -> Text("Looking for PulsoxRelay…")
                BleFirmwareUpdateClient.UpdateState.Connecting -> Text("Connecting…")
                BleFirmwareUpdateClient.UpdateState.Success ->
                    Text(
                        "Update succeeded — the device is rebooting into the new firmware.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                is BleFirmwareUpdateClient.UpdateState.Failed ->
                    Text("Update failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                BleFirmwareUpdateClient.UpdateState.Idle -> Unit
            }

            when (val state = checkState) {
                SettingsViewModel.FirmwareCheckState.Idle -> Unit
                SettingsViewModel.FirmwareCheckState.Checking -> Text("Checking for updates…")
                is SettingsViewModel.FirmwareCheckState.UpToDate ->
                    Text("Up to date (${state.version}).")
                is SettingsViewModel.FirmwareCheckState.Error ->
                    Text("Check failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                is SettingsViewModel.FirmwareCheckState.UpdateAvailable -> {
                    val currentText = state.currentVersion?.let { "current: $it" } ?: "current device version unknown"
                    Text("Update available: ${state.release.version} ($currentText)")
                    Button(
                        onClick = { viewModel.downloadAndStartFirmwareUpdate(state.release) },
                        enabled = !uploading,
                    ) {
                        Text("Download and install")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::checkForFirmwareUpdate,
                    enabled = checkState != SettingsViewModel.FirmwareCheckState.Checking && !uploading,
                ) {
                    Text("Check for update")
                }
                if (uploading) {
                    OutlinedButton(onClick = viewModel::cancelFirmwareUpdate) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Live mirror of [com.oxipulse.pulsoximetergraphs.data.ble.BleDebugLog], the app's own trace of
 * the last BLE sync (connection state, negotiated MTU/PHY, per-transfer throughput) — for
 * diagnosing a sync without adb/logcat access. "Copy log" puts the whole thing on the clipboard
 * in one tap; the text is also directly selectable if only part of it is needed.
 */
@Composable
private fun DebugLogSection(debugLog: String, onClear: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("BLE Debug Log", style = MaterialTheme.typography.titleMedium)
        Text(
            "Trace of the last BLE sync attempt — run a sync, then copy this and share it.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = debugLog.ifBlank { "(empty — run a BLE sync to populate this)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { clipboardManager.setText(AnnotatedString(debugLog)) },
                enabled = debugLog.isNotBlank(),
            ) {
                Text("Copy log")
            }
            OutlinedButton(onClick = onClear, enabled = debugLog.isNotBlank()) {
                Text("Clear")
            }
        }
    }
}

/**
 * Direct CSV import (SAF file picker), independent of a BLE sync — moved here from the graphs
 * screen's top app bar so device settings, OTA/WiFi, and both ways of getting readings into the
 * app live under one Settings destination.
 */
@Composable
private fun ImportTab(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        statusMessage = null
        // The activity-result callback runs on the main thread, and reading through a SAF
        // content-provider stream is a blocking IPC round trip — for anything but a tiny file
        // this would otherwise freeze the UI for a couple of seconds right after picking it.
        coroutineScope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text != null) {
                viewModel.importCsvText(text) { inserted, skipped ->
                    statusMessage = "Imported $inserted rows ($skipped skipped)"
                }
            } else {
                statusMessage = "Could not read that file"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import CSV", style = MaterialTheme.typography.titleMedium)
        Text(
            "Import a CSV file (DATE,TIME,SPO2,PULSE format) directly, without a BLE sync — " +
                "e.g. a file exported earlier or shared from another device.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { openDocumentLauncher.launch(arrayOf("text/csv", "*/*")) }) {
            Text("Choose CSV file")
        }
        statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
