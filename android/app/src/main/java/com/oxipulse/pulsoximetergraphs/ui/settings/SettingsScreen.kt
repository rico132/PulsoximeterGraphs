package com.oxipulse.pulsoximetergraphs.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxipulse.pulsoximetergraphs.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(appContainer.thresholdsRepository, appContainer.bleGattClient),
    )
    val config by viewModel.config.collectAsState()
    val testModeEnabled by viewModel.testModeEnabled.collectAsState()
    val debugLog by viewModel.debugLog.collectAsState()

    // Local editable draft, seeded from the persisted config and reset whenever it changes
    // externally (e.g. after a successful save round-trips a new StateFlow value).
    var draft by remember(config) { mutableStateOf(config) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Thresholds", style = MaterialTheme.typography.titleMedium)

            ThresholdNumberField("SpO2 orange", draft.spo2Orange) { draft = draft.copy(spo2Orange = it) }
            ThresholdNumberField("SpO2 red", draft.spo2Red) { draft = draft.copy(spo2Red = it) }
            ThresholdNumberField("Pulse low orange", draft.pulseLowOrange) { draft = draft.copy(pulseLowOrange = it) }
            ThresholdNumberField("Pulse low red", draft.pulseLowRed) { draft = draft.copy(pulseLowRed = it) }
            ThresholdNumberField("Pulse high orange", draft.pulseHighOrange) { draft = draft.copy(pulseHighOrange = it) }
            ThresholdNumberField("Pulse high red", draft.pulseHighRed) { draft = draft.copy(pulseHighRed = it) }

            val liveError = draft.validate()
            if (liveError != null) {
                Text(liveError, color = MaterialTheme.colorScheme.error)
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Button(
                onClick = {
                    val error = viewModel.save(draft)
                    errorMessage = error
                    savedMessage = if (error == null) "Saved" else null
                },
                enabled = liveError == null,
                modifier = Modifier.wrapContentSize(),
            ) {
                Text("Save")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DeviceSection(viewModel, testModeEnabled)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DebugLogSection(debugLog, onClear = viewModel::clearDebugLog)
        }
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
    val connected = viewModel.isDeviceConnected()
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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

                HorizontalDivider()

                Text("WiFi credentials (for OTA)")
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("SSID") },
                    enabled = connected,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    enabled = connected,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.setWifiCredentials(ssid, password) },
                    enabled = connected && ssid.isNotBlank(),
                ) {
                    Text("Send WiFi credentials")
                }

                HorizontalDivider()

                Text(
                    "Bringing the ESP32 into OTA mode brings up WiFi (using stored " +
                        "credentials, or a captive-portal AP if none are stored yet) so new " +
                        "firmware can be flashed with `pio run -t upload --upload-port <ip>`.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { viewModel.enterOtaMode() },
                    enabled = connected,
                ) {
                    Text("Enter OTA mode")
                }

                if (!connected) {
                    Text(
                        "Connect to the device (via a BLE sync) to enable these controls.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
