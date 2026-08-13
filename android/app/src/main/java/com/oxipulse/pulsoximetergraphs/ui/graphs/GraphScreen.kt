package com.oxipulse.pulsoximetergraphs.ui.graphs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.di.AppContainer
import com.oxipulse.pulsoximetergraphs.ui.rangepicker.DateTimeRangePickerDialog
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    appContainer: AppContainer,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: GraphViewModel = viewModel(
        factory = GraphViewModel.factory(
            appContainer.readingsRepository,
            appContainer.thresholdsRepository,
            appContainer.bleGattClient,
        ),
    )

    val readings by viewModel.readings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val thresholdConfig by viewModel.thresholdConfig.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    var showRangePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
        if (text != null) {
            viewModel.importCsvText(text) { inserted, skipped ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Imported $inserted rows ($skipped skipped)")
                }
            }
        }
    }

    // Surface BLE sync completion/failure as a snackbar, then reset state so re-tapping the
    // sync button starts a fresh sync instead of showing a stale result.
    LaunchedEffect(syncState) {
        when (val state = syncState) {
            is BleGattClient.SyncState.Success -> {
                snackbarHostState.showSnackbar(
                    "Synced: ${state.rowsInserted} rows (${state.rowsSkipped} skipped)",
                )
                viewModel.resetSyncState()
            }
            is BleGattClient.SyncState.Failed -> {
                snackbarHostState.showSnackbar("Sync failed: ${state.message}")
                viewModel.resetSyncState()
            }
            else -> Unit
        }
    }

    if (showRangePicker) {
        DateTimeRangePickerDialog(
            initialRange = selectedRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { range ->
                viewModel.setRange(range)
                showRangePicker = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pulsoximeter Graphs") },
                actions = {
                    IconButton(onClick = { showRangePicker = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Select date range")
                    }
                    IconButton(onClick = { openDocumentLauncher.launch(arrayOf("text/csv", "*/*")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Import CSV")
                    }
                    IconButton(onClick = { viewModel.startBleSync() }) {
                        if (isSyncing(syncState)) {
                            CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        } else {
                            Icon(Icons.Filled.Bluetooth, contentDescription = "Sync via BLE")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RangeSummary(selectedRange)
            StatsPanel(stats)
            SpO2ChartCard(readings, thresholdConfig)
            PulseChartCard(readings, thresholdConfig)
        }
    }
}

private fun isSyncing(state: BleGattClient.SyncState): Boolean = when (state) {
    BleGattClient.SyncState.Idle,
    is BleGattClient.SyncState.Success,
    is BleGattClient.SyncState.Failed,
    -> false
    else -> true
}

@Composable
private fun RangeSummary(range: ClosedRange<Instant>) {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    Text(
        "${formatter.format(range.start.atZone(zone))}  —  ${formatter.format(range.endInclusive.atZone(zone))}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun StatsPanel(stats: ReadingStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatsRow("SpO2", stats.minSpo2, stats.maxSpo2, stats.avgSpo2, unit = "%")
            StatsRow("Pulse", stats.minPulse, stats.maxPulse, stats.avgPulse, unit = "bpm")
        }
    }
}

@Composable
private fun StatsRow(label: String, min: Int?, max: Int?, avg: Double?, unit: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (min == null || max == null || avg == null) {
            Text("no data", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "min $min$unit  ·  max $max$unit  ·  avg ${"%.1f".format(avg)}$unit",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Plotted by each reading's INDEX in the (already range-filtered) list, not by its raw epoch
// second. Vico derives its horizontal pixels-per-unit scale from the GCD of consecutive x
// deltas in the series; real readings are sampled roughly once a second during a session but
// can have arbitrarily large gaps between sessions, so using raw timestamps as x collapses
// that GCD to ~1 second while the selected range can span hours or days. That blows up the
// pixel math ((x - minX) / xStep * xSpacing) — only the very first cluster of points ends up
// on-screen and the rest is positioned far off-canvas, which looks like the chart ignoring
// the selected timespan, and makes any gap in the data break the whole chart rather than
// just leaving a blank stretch. Indices always have a delta of exactly 1, so this GCD issue
// can't happen regardless of how sparse or gappy the underlying data is — the axis label
// formatter below maps each index back to that reading's real timestamp for display.
@Composable
private fun rememberTimeAxisFormatter(readings: List<ReadingEntity>): CartesianValueFormatter =
    remember(readings) {
        CartesianValueFormatter { _, value, _ ->
            val reading = readings.getOrNull(value.toInt()) ?: return@CartesianValueFormatter ""
            val instant = Instant.ofEpochSecond(reading.timestampEpochSec)
            DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(ZoneId.systemDefault()))
        }
    }

@Composable
private fun SpO2ChartCard(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(readings) {
        modelProducer.runTransaction {
            // A series must be non-empty (Vico throws otherwise), so only add one when there's
            // actually data for the selected range — an empty transaction just renders no line.
            if (readings.isNotEmpty()) {
                lineModel {
                    series(
                        x = readings.indices.map { it.toDouble() },
                        y = readings.map { it.spo2.toDouble() },
                    )
                }
            }
        }
    }
    val bands = rememberSpo2ThresholdBands(thresholdConfig)
    val timeAxisFormatter = rememberTimeAxisFormatter(readings)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SpO2 (%)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ProvideVicoTheme(rememberM3VicoTheme()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = timeAxisFormatter),
                        decorations = bands,
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        }
    }
}

@Composable
private fun PulseChartCard(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(readings) {
        modelProducer.runTransaction {
            if (readings.isNotEmpty()) {
                lineModel {
                    series(
                        x = readings.indices.map { it.toDouble() },
                        y = readings.map { it.pulse.toDouble() },
                    )
                }
            }
        }
    }
    val bands = rememberPulseThresholdBands(thresholdConfig)
    val timeAxisFormatter = rememberTimeAxisFormatter(readings)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pulse (bpm)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ProvideVicoTheme(rememberM3VicoTheme()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = timeAxisFormatter),
                        decorations = bands,
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
        }
    }
}
