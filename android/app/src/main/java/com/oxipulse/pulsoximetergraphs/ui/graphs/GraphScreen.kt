package com.oxipulse.pulsoximetergraphs.ui.graphs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.di.AppContainer
import com.oxipulse.pulsoximetergraphs.ui.rangepicker.DateTimeRangePickerDialog
import com.oxipulse.pulsoximetergraphs.ui.rangepicker.PredefinedTimeSpan
import com.oxipulse.pulsoximetergraphs.ui.theme.extendedColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    appContainer: AppContainer,
    onOpenSettings: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
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
    val canZoomOut by viewModel.canZoomOut.collectAsState()

    var showRangePicker by remember { mutableStateOf(false) }
    var showRangeMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    // The dialog (below) is now the only place sync progress shows — the toolbar icon stays a
    // plain, static Bluetooth glyph regardless of sync state. CSV import has moved to Settings'
    // Import tab, and the zoom in/out buttons that briefly lived atop the charts card are gone
    // again in favor of a single Undo button here, undoing the last range change of any kind
    // (zoom or manual date pick alike — see GraphViewModel.zoomOut's own doc).
    if (isSyncing(syncState)) {
        BleSyncDialog(syncState = syncState, onCancel = viewModel::cancelSync)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pulsoximeter Graphs") },
                actions = {
                    IconButton(onClick = { viewModel.zoomOut() }, enabled = canZoomOut) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo zoom")
                    }
                    // A quick-select menu of PredefinedTimeSpan entries, plus a "Custom range…"
                    // entry that falls through to the existing full DateTimeRangePickerDialog —
                    // the same "Select date range" icon now offers both instead of the dialog
                    // being the only way in.
                    Box {
                        IconButton(onClick = { showRangeMenu = true }) {
                            Icon(Icons.Filled.DateRange, contentDescription = "Select date range")
                        }
                        DropdownMenu(expanded = showRangeMenu, onDismissRequest = { showRangeMenu = false }) {
                            for (span in PredefinedTimeSpan.entries) {
                                DropdownMenuItem(
                                    text = { Text(span.label) },
                                    onClick = {
                                        showRangeMenu = false
                                        viewModel.setRange(span.toRange())
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Custom range…") },
                                onClick = {
                                    showRangeMenu = false
                                    showRangePicker = true
                                },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.startBleSync() }) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Sync via BLE")
                    }
                    // Icon shows the mode a tap switches TO, not the current one.
                    IconButton(onClick = onToggleTheme) {
                        if (isDarkTheme) {
                            Icon(Icons.Filled.LightMode, contentDescription = "Switch to light theme")
                        } else {
                            Icon(Icons.Filled.DarkMode, contentDescription = "Switch to dark theme")
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
        // Pinch/drag zoom is intentionally disabled (zoomEnabled/scrollEnabled = false below) in
        // favor of drag-to-zoom (see DragToZoomOverlay): Vico's charts don't render more of the
        // underlying data as you zoom in, only the same already-decimated points bigger, so
        // interactive zoom here would just be a magnifying glass, not more detail. Dragging a
        // selection instead narrows the *selected range itself*, which re-queries the DB for just
        // that window and (usually) renders it at full resolution. Shared between both charts
        // purely because CartesianChartHost requires a scroll/zoom state instance per chart, not
        // because they need to stay in sync anymore — with interactivity off there's no live state
        // to drift out of sync in the first place.
        val sharedZoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content)
        val sharedScrollState = rememberVicoScrollState(scrollEnabled = false)
        // Capped and decimated once for the currently selected range — see decimateKeepingExtremes.
        // Dragging a selection (below) replaces this with a narrower selectedRange rather than
        // trying to re-decimate this same list on the fly.
        val plottedReadings = remember(readings) { decimateKeepingExtremes(readings) }
        // Same "does this need a date label" check rememberTimeAxisFormatter makes per-chart (see
        // needsDateLabel's own doc) — computed once more here purely to size the shared item
        // placer's label spacing to match the (possibly longer, date-including) labels it'll
        // place, not to duplicate the formatting decision itself.
        val zone = remember { ZoneId.systemDefault() }
        val multiDayLabels = remember(plottedReadings, zone) { needsDateLabel(plottedReadings, zone) }
        // Shared between both charts' bottom axes so they pick the exact same tick indices —
        // see the parameter doc on rememberSharedTimeAxisItemPlacer for why leaving each chart
        // to compute its own (Vico's default) caused SpO2 and Pulse to show different times for
        // what's supposed to be the same x-position.
        val timeAxisItemPlacer = rememberSharedTimeAxisItemPlacer(plottedReadings.size, multiDayLabels)

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatsPanel(selectedRange, stats)
            ChartsCard(
                readings = plottedReadings,
                thresholdConfig = thresholdConfig,
                stats = stats,
                zoomState = sharedZoomState,
                scrollState = sharedScrollState,
                onRangeSelected = viewModel::setRange,
                timeAxisItemPlacer = timeAxisItemPlacer,
                canZoomOut = canZoomOut,
            )
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

/**
 * Whether [BleGattClient.cancelSync] would actually do anything right now — mirrors its own
 * guard exactly, so the dialog's Cancel button only ever appears enabled when a tap on it is
 * guaranteed to abort without saving. Once past ReceivingData (Inserting/ClearingBuffer), the
 * transfer is already complete and insertion is a single fast batch write, so there's normally
 * nothing left to meaningfully cancel — see cancelSync's own doc for why.
 */
private fun isCancelable(state: BleGattClient.SyncState): Boolean = when (state) {
    BleGattClient.SyncState.Scanning,
    BleGattClient.SyncState.Connecting,
    BleGattClient.SyncState.RequestingData,
    is BleGattClient.SyncState.ReceivingData,
    is BleGattClient.SyncState.Retrying,
    -> true
    else -> false
}

/**
 * Human-readable label for whichever step of PROTOCOL.md's sync sequence is currently in
 * flight. [BleGattClient.SyncState.ReceivingData] carries a live byte count straight from the
 * Data characteristic's notifications, so this updates continuously while the transfer is
 * running rather than sitting on one static label — mirrors the per-chunk progress the sending
 * side (tools/ble_csv_sender.py) prints. Only called while [isSyncing] is true (see
 * [BleSyncDialog]); Idle/Success/Failed are surfaced via the snackbar instead (the
 * LaunchedEffect(syncState) in [GraphScreen]).
 */
private fun syncStatusText(state: BleGattClient.SyncState): String = when (state) {
    BleGattClient.SyncState.Scanning -> "Scanning for PulsoxRelay…"
    BleGattClient.SyncState.Connecting -> "Connecting…"
    BleGattClient.SyncState.RequestingData -> "Requesting data…"
    is BleGattClient.SyncState.ReceivingData -> receivingDataText(state)
    BleGattClient.SyncState.Inserting -> "Saving to database…"
    BleGattClient.SyncState.ClearingBuffer -> "Finishing up…"
    is BleGattClient.SyncState.Retrying -> "Sync stalled — retrying (${state.attempt}/${state.maxAttempts})…"
    BleGattClient.SyncState.Idle,
    is BleGattClient.SyncState.Success,
    is BleGattClient.SyncState.Failed,
    -> ""
}

/**
 * The total byte count (and hence a percentage) is only known once the sender's multi-file
 * header has been parsed (see BleGattClient.MultiFileMeta) — legacy single-file transfers (the
 * real ESP32, or the sender script's single-file path) never declare a total upfront, so this
 * falls back to a plain running byte count for those.
 */
private fun receivingDataText(state: BleGattClient.SyncState.ReceivingData): String {
    val filePrefix = if (state.fileCount != null && state.fileCount > 1) {
        "File ${state.fileIndex}/${state.fileCount} — "
    } else {
        ""
    }
    val total = state.totalBytes
    return if (total != null && total > 0) {
        val percent = (state.bytesReceived * 100 / total).coerceIn(0, 100)
        "${filePrefix}Receiving data… $percent%"
    } else {
        "${filePrefix}Receiving data… (${state.bytesReceived} bytes)"
    }
}

/**
 * A modal dialog over the whole graphs screen showing live sync progress, with a Cancel button
 * that aborts without saving (see [BleGattClient.cancelSync]) — replaces the old inline status
 * row above the charts and the toolbar's spinning Bluetooth icon; that icon is now a plain,
 * static glyph regardless of sync state, since this dialog is the only place progress shows.
 * [GraphScreen] only composes this while [isSyncing] is true, so it disappears the instant the
 * sync finishes, fails, or is cancelled — the final result (success/failure) is surfaced via a
 * snackbar instead, from the same syncState the dialog was just showing. `onDismissRequest` is
 * deliberately a no-op: tapping outside the dialog or pressing back must NOT cancel a sync that's
 * actually in flight (easy to trigger by accident) — the Cancel button is the only manual way out.
 */
@Composable
private fun BleSyncDialog(syncState: BleGattClient.SyncState, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Syncing via Bluetooth") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BleSyncSpinner()
                Text(syncStatusText(syncState), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = isCancelable(syncState)) {
                Text("Cancel")
            }
        },
    )
}

/**
 * A small rotating ring shown in [BleSyncDialog]. Material's default
 * [androidx.compose.material3.CircularProgressIndicator] ships at its own much larger default
 * track/size and only had `height` constrained here (not `width`), so it rendered oversized and
 * lopsided next to the dialog's text instead of reading as a compact "still working" spinner.
 */
@Composable
private fun BleSyncSpinner(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "ble-sync-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, easing = LinearEasing)),
        label = "rotation",
    )
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(24.dp)) {
        rotate(rotation) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
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

/**
 * The selected time span now lives at the top of this card (previously a separate [Text] floating
 * above it) — it's a summary of exactly the data the stats/charts below are for, so it reads
 * better as this card's own header than as an unrelated line sitting above it.
 */
@Composable
private fun StatsPanel(range: ClosedRange<Instant>, stats: ReadingStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeSummary(range)
            HorizontalDivider()
            Text("Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatsTable(stats)
            HorizontalDivider()
            EventsRow(stats)
        }
    }
}

/**
 * A proper table (metric name + one column per statistic) instead of one concatenated string per
 * row: SpO2's "%" and Pulse's "bpm" differ in length, so appending the unit straight onto each
 * number ("92%" vs "92bpm") shifted every later number in that row sideways by a different amount
 * per metric — nothing lined up between the SpO2 and Pulse rows. Each column here is a fixed
 * weight instead, so min/max/avg/p95 all land in the same horizontal position regardless of which
 * row they're in; the unit moves into the metric-name cell (once per row) instead of being
 * repeated on every value.
 */
@Composable
private fun StatsTable(stats: ReadingStats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatsTableRow(
            metric = "",
            min = "Min",
            max = "Max",
            avg = "Avg",
            p95 = "P95",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        StatsTableRow(
            metric = "SpO2 (%)",
            min = stats.minSpo2?.toString() ?: "–",
            max = stats.maxSpo2?.toString() ?: "–",
            avg = stats.avgSpo2?.let { "%.1f".format(it) } ?: "–",
            p95 = stats.p95Spo2?.toString() ?: "–",
        )
        StatsTableRow(
            metric = "Pulse (bpm)",
            min = stats.minPulse?.toString() ?: "–",
            max = stats.maxPulse?.toString() ?: "–",
            avg = stats.avgPulse?.let { "%.1f".format(it) } ?: "–",
            p95 = stats.p95Pulse?.toString() ?: "–",
        )
    }
}

/**
 * A single desaturation-event count, shown as its own row rather than folded into [StatsTable]:
 * it's one number, not a min/max/avg/p95 tuple, so it doesn't fit that table's fixed 4-column
 * shape — see [countSpo2Events]'s own doc for exactly what counts as one event.
 */
@Composable
private fun EventsRow(stats: ReadingStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Events (SpO2 < $SPO2_EVENT_THRESHOLD_PERCENT%)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stats.spo2EventCount?.toString() ?: "–",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatsTableRow(
    metric: String,
    min: String,
    max: String,
    avg: String,
    p95: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(metric, style = style, fontWeight = fontWeight, modifier = Modifier.weight(1.4f))
        for (value in listOf(min, max, avg, p95)) {
            Text(
                value,
                style = style,
                fontWeight = fontWeight,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
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
/**
 * Whether [readings] (assumed sorted ascending by timestamp — see ReadingDao's `ORDER BY
 * timestampEpochSec ASC`) contains anything that isn't from today, comparing only the first and
 * last reading against [zone]'s current date rather than scanning the whole list — sufficient for
 * a sorted list (every date in between necessarily falls within [firstDate, lastDate]), and O(1)
 * instead of O(n) against what can be a many-thousand-row selection.
 *
 * Deliberately checked against "today" rather than "does [readings] itself span more than one
 * date": drag-to-zoom (see DragToZoomOverlay/GraphViewModel.setRange) narrows the plotted list to
 * whatever the drag selected, which — even when the *original* selection was many days wide —
 * almost always lands within a single calendar day once zoomed in. A plain first-vs-last-date
 * comparison on that narrowed list would then read as "single day" and drop the date label, even
 * though the day being viewed might be a week ago rather than today — leaving no way to tell which
 * day a zoomed-in chart is actually showing. Anchoring to "today" instead means the date only ever
 * disappears once the user has zoomed all the way down to (or started from) data that's
 * unambiguously today, regardless of how far they've zoomed in to get there.
 */
private fun needsDateLabel(readings: List<ReadingEntity>, zone: ZoneId): Boolean {
    if (readings.isEmpty()) return false
    val today = LocalDate.now(zone)
    val firstDate = Instant.ofEpochSecond(readings.first().timestampEpochSec).atZone(zone).toLocalDate()
    val lastDate = Instant.ofEpochSecond(readings.last().timestampEpochSec).atZone(zone).toLocalDate()
    return firstDate != today || lastDate != today
}

private val TIME_ONLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// Includes the date on every label once the selected range crosses a day boundary — a gap in the
// data (e.g. no readings at all on some day in between) would otherwise leave two clusters of
// points both labeled with a bare "HH:mm", with nothing on the chart itself indicating they're
// actually days apart rather than the same afternoon.
//
// Date and time are on separate lines (a real newline inside the quoted pattern literal, not a
// wider single-line "d MMM, HH:mm") because Vico's default bottom-axis label component renders a
// single line and silently ellipsizes whatever doesn't fit in it — "22 Aug, 14:30" doesn't, and
// was rendering as "22 Aug, 14…" with the time cut off entirely. rememberTimeAxisLabelComponent
// below opts the label component into 2 lines so this actually wraps instead of truncating; each
// line alone ("22 Aug" / "14:30") is no wider than the single-line "14:30"-only label already
// rendered fine in the non-multi-day case, so this fits with room to spare.
private val DATE_AND_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM'\n'HH:mm")

@Composable
private fun rememberTimeAxisFormatter(readings: List<ReadingEntity>): CartesianValueFormatter {
    val zone = ZoneId.systemDefault()
    return remember(readings, zone) {
        val formatter = if (needsDateLabel(readings, zone)) DATE_AND_TIME_FORMATTER else TIME_ONLY_FORMATTER
        CartesianValueFormatter { _, value, _ ->
            // Vico's contract forbids ever returning a blank string here — it throws if we do
            // (see the crash this guards against). That's harder to guarantee than it looks:
            // CartesianChartModelProducer.runTransaction (below) commits a new, possibly
            // shorter series *asynchronously*, while this formatter is rebuilt *synchronously*
            // on the very same recomposition that changed `readings`. So right after picking a
            // new range, the axis can still be laid out against the previous (longer) committed
            // model for a frame or two while this formatter already reflects the new (shorter)
            // list — i.e. the index the axis asks for can legitimately be out of bounds for a
            // moment, through no misuse of the API. Clamping instead of bailing out means that
            // transient mismatch just reuses the nearest edge reading's label for a frame
            // instead of crashing the app; a plain "" fallback for the truly-empty case would
            // itself be blank (and .isBlank() also rejects whitespace-only strings), so that
            // case gets a real placeholder instead.
            if (readings.isEmpty()) return@CartesianValueFormatter "–"
            val reading = readings[value.toInt().coerceIn(readings.indices)]
            val instant = Instant.ofEpochSecond(reading.timestampEpochSec)
            formatter.format(instant.atZone(zone))
        }
    }
}

private const val TARGET_TIME_AXIS_LABEL_COUNT = 6

// Fewer, wider-spaced labels once dates are included (see DATE_AND_TIME_FORMATTER) — even split
// across 2 lines, "22 Aug" / "14:30" reads more comfortably with more breathing room between
// ticks than plain "14:30" needs, and TARGET_TIME_AXIS_LABEL_COUNT's fixed spacing has no
// awareness of actual label pixel width to widen itself automatically (see
// rememberSharedTimeAxisItemPlacer's own doc for why spacing is computed this way at all).
private const val TARGET_TIME_AXIS_LABEL_COUNT_MULTI_DAY = 4

/**
 * The bottom axis's label component, shared by both chart cards so a multi-day selection wraps
 * identically on each. Vico's default axis label component renders a single line and silently
 * ellipsizes any label that doesn't fit within it — harmless for the plain "HH:mm" labels used
 * outside a multi-day selection, but it was truncating DATE_AND_TIME_FORMATTER's output (e.g.
 * "22 Aug, 14:30" rendered as "22 Aug, 14…", losing the time entirely — see that formatter's own
 * doc). Allowing 2 lines here doesn't force wrapping for the single-line "HH:mm" case; a
 * formatted value only spans 2 lines if it actually contains the newline DATE_AND_TIME_FORMATTER
 * puts there, so one label component works for both.
 */
@Composable
private fun rememberTimeAxisLabelComponent() = rememberAxisLabelComponent(lineCount = 2)

/**
 * A [HorizontalAxis.ItemPlacer] shared by both chart cards' bottom axes, so they land on the
 * exact same label indices instead of each computing its own.
 *
 * Vico's default `HorizontalAxis.ItemPlacer.aligned()` auto-widens its tick spacing to avoid
 * overlapping labels, based on each chart's *own* available plot width — which is the chart's
 * total width minus whatever its vertical axis's label gutter reserves. SpO2 and Pulse render
 * different-width gutters (their values have different typical digit counts, e.g. "45" vs
 * "100"), so each chart ends up with a slightly different plot width and picks a different tick
 * spacing — visibly, the SpO2 chart labeling a point "5:46" right where the Pulse chart labels
 * the same x-position "5:45", even though both plot the exact same [ReadingEntity] list by the
 * exact same index. Since both charts already share one x-domain (identical indices, xStep
 * always 1 — see rememberTimeAxisFormatter above), tick placement here is instead computed from
 * point count alone via an explicit `spacing`, with `addExtremeLabelPadding` off — that flag is
 * what reintroduces a pixel-width-dependent multiplier even with an explicit spacing (see
 * AlignedHorizontalAxisItemPlacer.getLabelValues), so turning it off is what actually makes this
 * deterministic across the two charts rather than just "usually the same."
 */
@Composable
private fun rememberSharedTimeAxisItemPlacer(pointCount: Int, multiDay: Boolean): HorizontalAxis.ItemPlacer =
    remember(pointCount, multiDay) {
        val targetCount = if (multiDay) TARGET_TIME_AXIS_LABEL_COUNT_MULTI_DAY else TARGET_TIME_AXIS_LABEL_COUNT
        val spacing = (pointCount / targetCount).coerceAtLeast(1)
        HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }, addExtremeLabelPadding = false)
    }

/**
 * The Y axis is padded out to the nearest multiple of 5 beyond the actual min/max for the
 * selected range, so the data never touches the plot's top/bottom edge exactly. A value that's
 * already an exact multiple of 5 is pushed out by one more step rather than left as-is (e.g.
 * min=60 -> 55, not 60; max=90 -> 95, not 90), so there's always at least a little headroom.
 */
private fun floorToMultipleOf5(value: Int): Int = Math.floorDiv(value - 1, 5) * 5

private fun ceilToMultipleOf5(value: Int): Int = Math.floorDiv(value, 5) * 5 + 5

/**
 * Wraps already-computed (padded) [minY]/[maxY] bounds as a fixed Y range, or leaves Vico on
 * its default auto-scaling when there's no data (null) for the selected range.
 */
private fun fixedYRange(minY: Int?, maxY: Int?): CartesianLayerRangeProvider =
    if (minY != null && maxY != null) {
        CartesianLayerRangeProvider.fixed(minY = minY.toDouble(), maxY = maxY.toDouble())
    } else {
        CartesianLayerRangeProvider.auto()
    }

private const val TARGET_Y_AXIS_LABEL_COUNT = 5
private val NICE_STEP_FRACTIONS = doubleArrayOf(1.0, 2.0, 5.0, 10.0)

/**
 * "Nice numbers" step chooser for the Y axis: a step of {1, 2, 5} times a power of ten, sized so
 * roughly [TARGET_Y_AXIS_LABEL_COUNT] labels show regardless of how wide or narrow [minY]/[maxY]
 * (already padded by [floorToMultipleOf5]/[ceilToMultipleOf5]) end up being.
 *
 * Vico's own default vertical-axis step (used when no [VerticalAxis.ItemPlacer] is passed to
 * [VerticalAxis.rememberStart]) picks its step purely from the order of magnitude of `maxY` —
 * see StepVerticalAxisItemPlacer's `requestedOrDefaultStep`, `10.0.pow(floor(log10(maxY)) - 1)` —
 * which implicitly assumes the axis starts near zero. That's exactly wrong for this app's fixed,
 * non-zero-based ranges: a mostly-normal SpO2 session with minY=85/maxY=100 gets a default step
 * of 10 from that heuristic (10^(floor(log10(100))-1) = 10^1), leaving only two or three labels
 * visible across a 15-point span that reads far more clearly labeled every 5. Computing the step
 * from the actual span instead keeps the label count roughly constant no matter how tight the
 * visible range is.
 */
private fun niceYAxisStep(minY: Int, maxY: Int): Double {
    val span = (maxY - minY).toDouble()
    if (span <= 0) return 1.0
    val roughStep = span / TARGET_Y_AXIS_LABEL_COUNT
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val niceFraction = NICE_STEP_FRACTIONS.first { it * magnitude >= roughStep }
    return niceFraction * magnitude
}

/**
 * A [VerticalAxis.ItemPlacer] that always labels exactly [minY] and [maxY] (the actual bounds of
 * the fixed range — see [fixedYRange]), plus intermediate values at [niceYAxisStep]'s step in
 * between. [VerticalAxis.ItemPlacer.step] looked like the right built-in tool for this, but its
 * overlap guard (`StepVerticalAxisItemPlacer`'s `minStep`, derived from measured label height vs.
 * available axis height) can silently widen a requested step past what was asked for — confirmed
 * by reading Vico 3.2.3's actual source: passing an explicit step still let minStep round it up to
 * a *larger* one when the height/label-height math decided there wasn't room, which is exactly
 * how a first attempt at this fix still left minY/maxY unlabeled on a tight range. That's
 * backwards from what's wanted here: this app deliberately shows a tight, non-zero-based Y range
 * and wants it labeled finely with both ends always visible, not defended against overlap by a
 * heuristic tuned for zero-based axes. Bypassing Vico's step machinery entirely and supplying a
 * fixed, precomputed label list guarantees both.
 */
private class FixedStepVerticalAxisItemPlacer(minY: Int, maxY: Int) : VerticalAxis.ItemPlacer {
    private val labelValues: List<Double> = buildList {
        val step = niceYAxisStep(minY, maxY)
        var value = minY.toDouble()
        while (value < maxY) {
            add(value)
            value += step
        }
        add(maxY.toDouble())
    }

    override fun getLabelValues(
        context: CartesianDrawingContext,
        axisHeight: Float,
        maxLabelHeight: Float,
        position: Axis.Position.Vertical,
    ): List<Double> = labelValues

    override fun getWidthMeasurementLabelValues(
        context: CartesianMeasuringContext,
        axisHeight: Float,
        maxLabelHeight: Float,
        position: Axis.Position.Vertical,
    ): List<Double> = labelValues

    override fun getHeightMeasurementLabelValues(
        context: CartesianMeasuringContext,
        position: Axis.Position.Vertical,
    ): List<Double> = labelValues

    // Geometry formulas below match StepVerticalAxisItemPlacer's own (for shiftTopLines = true,
    // the interface's default we don't override) — generic margin math independent of step size,
    // not part of the overlap-widening behavior this class exists to bypass.
    override fun getTopLayerMargin(
        context: CartesianMeasuringContext,
        verticalLabelPosition: Position.Vertical,
        maxLabelHeight: Float,
        maxLineThickness: Float,
    ): Float = when (verticalLabelPosition) {
        Position.Vertical.Top -> maxLabelHeight + maxLineThickness / 2f
        Position.Vertical.Center -> (max(maxLabelHeight, maxLineThickness) + maxLineThickness) / 2f
        Position.Vertical.Bottom -> maxLineThickness
    }

    override fun getBottomLayerMargin(
        context: CartesianMeasuringContext,
        verticalLabelPosition: Position.Vertical,
        maxLabelHeight: Float,
        maxLineThickness: Float,
    ): Float = when (verticalLabelPosition) {
        Position.Vertical.Top -> maxLineThickness
        Position.Vertical.Center -> (max(maxLabelHeight, maxLineThickness) + maxLineThickness) / 2f
        Position.Vertical.Bottom -> maxLabelHeight + maxLineThickness / 2f
    }
}

/**
 * Y-axis item placer using [FixedStepVerticalAxisItemPlacer], or Vico's own default when there's
 * no data for the selected range (matches [fixedYRange]'s null-range fallback to auto-scaling).
 */
@Composable
private fun rememberYAxisItemPlacer(minY: Int?, maxY: Int?): VerticalAxis.ItemPlacer =
    remember(minY, maxY) {
        if (minY != null && maxY != null) {
            FixedStepVerticalAxisItemPlacer(minY, maxY)
        } else {
            VerticalAxis.ItemPlacer.step()
        }
    }

private const val MAX_PLOTTED_POINTS = 500

/**
 * Vico doesn't cull off-screen points, so line-rendering cost (and pan/zoom smoothness) scales
 * with point count regardless of how zoomed in the user currently is — a big CSV import or a
 * wide, densely-sampled date range could otherwise mean tracing tens of thousands of points on
 * every frame. Cap it by splitting the range into buckets and keeping each bucket's local
 * min/max for *both* SpO2 and pulse (not just picking every Nth reading), so a brief
 * desaturation or a short spike still shows up on the chart instead of silently getting
 * skipped — this is a monitoring app, so hiding a real (if brief) out-of-range reading purely
 * for smoother scrolling isn't an acceptable trade. Both charts must decimate to the exact same
 * indices (computed once, shared) for the pan/zoom sync above to actually line them up.
 */
private fun decimateKeepingExtremes(readings: List<ReadingEntity>): List<ReadingEntity> {
    if (readings.size <= MAX_PLOTTED_POINTS) return readings
    val bucketCount = (MAX_PLOTTED_POINTS / 4).coerceAtLeast(1)
    val bucketSize = (readings.size + bucketCount - 1) / bucketCount
    val kept = sortedSetOf<Int>()
    var bucketStart = 0
    while (bucketStart < readings.size) {
        val bucketEnd = (bucketStart + bucketSize).coerceAtMost(readings.size)
        var minSpo2 = bucketStart
        var maxSpo2 = bucketStart
        var minPulse = bucketStart
        var maxPulse = bucketStart
        for (i in bucketStart until bucketEnd) {
            if (readings[i].spo2 < readings[minSpo2].spo2) minSpo2 = i
            if (readings[i].spo2 > readings[maxSpo2].spo2) maxSpo2 = i
            if (readings[i].pulse < readings[minPulse].pulse) minPulse = i
            if (readings[i].pulse > readings[maxPulse].pulse) maxPulse = i
        }
        kept += minSpo2
        kept += maxSpo2
        kept += minPulse
        kept += maxPulse
        bucketStart = bucketEnd
    }
    return kept.map { readings[it] }
}

/** Below this fraction of the chart's width, a drag is treated as an accidental tap/jitter, not a zoom. */
private const val MIN_DRAG_FRACTION = 0.03f

/**
 * Wraps [content] (a chart) with a horizontal drag-to-select gesture: dragging draws a
 * translucent band with a live time-range label, and releasing narrows the range to it via
 * [onRangeSelected]. Maps the drag's pixel position to an index in [readings] by straight
 * fraction-of-width, which is only accurate because the wrapped chart's own zoom/scroll is
 * disabled (see GraphScreen) — with pan/zoom off, index 0 always sits at the left edge of this
 * Box and the last index at the right edge, with no live pan offset to account for. This doesn't
 * correct for the vertical axis's label gutter eating into the left edge of that width, so the
 * mapping is approximate near the edges — acceptable for "roughly select a section," and cheap
 * to redo since every drag is undoable via the toolbar's zoom-out button.
 */
@Composable
private fun DragToZoomOverlay(
    readings: List<ReadingEntity>,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var dragStartX by remember { mutableStateOf<Float?>(null) }
    var dragCurrentX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(0f) }

    fun indexAt(x: Float): Int =
        if (widthPx <= 0f) {
            0
        } else {
            (x / widthPx * (readings.size - 1)).roundToInt().coerceIn(readings.indices)
        }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(readings) {
                if (readings.size < 2) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        dragCurrentX = offset.x
                    },
                    onDragEnd = {
                        val startX = dragStartX
                        val endX = dragCurrentX
                        if (startX != null && endX != null && widthPx > 0f &&
                            kotlin.math.abs(endX - startX) / widthPx >= MIN_DRAG_FRACTION
                        ) {
                            val loIndex = indexAt(minOf(startX, endX))
                            val hiIndex = indexAt(maxOf(startX, endX))
                            if (hiIndex > loIndex) {
                                onRangeSelected(
                                    Instant.ofEpochSecond(readings[loIndex].timestampEpochSec)..
                                        Instant.ofEpochSecond(readings[hiIndex].timestampEpochSec),
                                )
                            }
                        }
                        dragStartX = null
                        dragCurrentX = null
                    },
                    onDragCancel = {
                        dragStartX = null
                        dragCurrentX = null
                    },
                    onHorizontalDrag = { change, _ -> dragCurrentX = change.position.x },
                )
            },
    ) {
        content()

        val startX = dragStartX
        val endX = dragCurrentX
        if (startX != null && endX != null && readings.size >= 2) {
            val selectionColor = MaterialTheme.colorScheme.primary
            val left = minOf(startX, endX)
            val right = maxOf(startX, endX)
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRect(
                    color = selectionColor.copy(alpha = 0.2f),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
                val edgeWidth = 1.dp.toPx()
                drawLine(selectionColor, Offset(left, 0f), Offset(left, size.height), strokeWidth = edgeWidth)
                drawLine(selectionColor, Offset(right, 0f), Offset(right, size.height), strokeWidth = edgeWidth)
            }
            val zone = ZoneId.systemDefault()
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            val loInstant = Instant.ofEpochSecond(readings[indexAt(left)].timestampEpochSec)
            val hiInstant = Instant.ofEpochSecond(readings[indexAt(right)].timestampEpochSec)
            Text(
                "${timeFormatter.format(loInstant.atZone(zone))} – ${timeFormatter.format(hiInstant.atZone(zone))}",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = selectionColor,
            )
        }
    }
}

// Thinner than Vico's 2dp default line, kept from the combined-chart layout this replaced —
// even with each metric back on its own card, a thinner line reads as less noisy against the
// threshold bands drawn behind it.
private val CHART_LINE_STROKE = LineCartesianLayer.LineStroke.Continuous(thickness = 1.2.dp)

/**
 * One shared card housing both the SpO2 and Pulse charts (stacked, each still its own
 * [CartesianChartHost] with its own scale and threshold bands — NOT one dual-axis chart: the
 * thresholds for the two metrics visually overlap in places, e.g. a pulse-high-orange band and an
 * spo2-red band can land at the same on-screen height once each is mapped onto its own axis,
 * which reads as one metric's danger zone bleeding into the other's when both are drawn on a
 * shared plot). [canZoomOut] isn't used for any button here (that's the top app bar's Undo button
 * now) — it's only forwarded to both charts as `zoomedIn`, since it also gates whether the Y axis
 * widens to always show threshold bands (see the comment on that in Spo2ChartContent).
 * `zoomState`/`scrollState` are shared between both charts purely because pinch/drag zoom is
 * disabled on both (see the comment on `sharedZoomState` in [GraphScreen]) — there's no live
 * pan/zoom to keep in sync, just a single state instance CartesianChartHost requires per chart.
 */
@Composable
private fun ChartsCard(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
    stats: ReadingStats,
    zoomState: VicoZoomState,
    scrollState: VicoScrollState,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    timeAxisItemPlacer: HorizontalAxis.ItemPlacer,
    canZoomOut: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spo2ChartContent(
                readings = readings,
                thresholdConfig = thresholdConfig,
                minSpo2 = stats.minSpo2,
                maxSpo2 = stats.maxSpo2,
                zoomState = zoomState,
                scrollState = scrollState,
                onRangeSelected = onRangeSelected,
                timeAxisItemPlacer = timeAxisItemPlacer,
                zoomedIn = canZoomOut,
            )
            PulseChartContent(
                readings = readings,
                thresholdConfig = thresholdConfig,
                minPulse = stats.minPulse,
                maxPulse = stats.maxPulse,
                zoomState = zoomState,
                scrollState = scrollState,
                onRangeSelected = onRangeSelected,
                timeAxisItemPlacer = timeAxisItemPlacer,
                zoomedIn = canZoomOut,
            )
        }
    }
}

@Composable
private fun Spo2ChartContent(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
    minSpo2: Int?,
    maxSpo2: Int?,
    zoomState: VicoZoomState,
    scrollState: VicoScrollState,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    timeAxisItemPlacer: HorizontalAxis.ItemPlacer,
    zoomedIn: Boolean,
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

    val spo2Color = MaterialTheme.extendedColors.chartSpo2
    val timeAxisFormatter = rememberTimeAxisFormatter(readings)
    // Widened to also cover spo2Red if the actual readings never dip that low: without this, a
    // user whose SpO2 stays comfortably in the high 90s would never see the red/orange bands at
    // all, since clamping them to a visible window that sits entirely above the bands collapses
    // both to zero height (see ThresholdBands.kt's clampToVisible) — same fix as Pulse below.
    // Only applied at the default (not zoomed-in) view, though: once the user has deliberately
    // narrowed the range (zoomIn/drag-to-zoom/date picker — anything that makes zoomOut
    // available), the whole point is to see that window's own data at a tighter scale, and
    // pinning the axis to a constant threshold-derived bound regardless of zoom is exactly what
    // made the Y axis look like it never responds to zooming in.
    // SpO2 is a percentage, so 100 is a hard ceiling regardless of the padding-to-5 rule.
    val minY = minSpo2?.let {
        floorToMultipleOf5(if (zoomedIn) it else minOf(it, thresholdConfig.spo2Red))
    }
    val maxY = maxSpo2?.let { ceilToMultipleOf5(it).coerceAtMost(100) }
    // Same rounded bounds passed to the range provider, so the bands clamp to exactly what's
    // on screen — see ThresholdBands.kt for why that clamp is necessary.
    val bands = rememberSpo2ThresholdBands(thresholdConfig, visibleMinY = minY?.toDouble(), visibleMaxY = maxY?.toDouble())
    val rangeProvider = remember(minY, maxY) { fixedYRange(minY, maxY) }
    val yAxisItemPlacer = rememberYAxisItemPlacer(minY, maxY)
    val spo2Line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(spo2Color)),
        stroke = CHART_LINE_STROKE,
    )
    val labelFontSize = MaterialTheme.typography.labelSmall.fontSize

    Column {
        Text("SpO2 (%)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ProvideVicoTheme(rememberM3VicoTheme()) {
            DragToZoomOverlay(
                readings = readings,
                onRangeSelected = onRangeSelected,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            ) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            lineProvider = LineCartesianLayer.LineProvider.series(spo2Line),
                            rangeProvider = rangeProvider,
                        ),
                        startAxis = VerticalAxis.rememberStart(
                            line = rememberAxisLineComponent(fill = Fill(spo2Color)),
                            label = rememberAxisLabelComponent(
                                style = TextStyle(color = spo2Color, fontSize = labelFontSize),
                            ),
                            itemPlacer = yAxisItemPlacer,
                        ),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            label = rememberTimeAxisLabelComponent(),
                            valueFormatter = timeAxisFormatter,
                            itemPlacer = timeAxisItemPlacer,
                        ),
                        decorations = bands,
                    ),
                    modelProducer = modelProducer,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PulseChartContent(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
    minPulse: Int?,
    maxPulse: Int?,
    zoomState: VicoZoomState,
    scrollState: VicoScrollState,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    timeAxisItemPlacer: HorizontalAxis.ItemPlacer,
    zoomedIn: Boolean,
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

    val pulseColor = MaterialTheme.extendedColors.chartPulse
    val timeAxisFormatter = rememberTimeAxisFormatter(readings)
    // Widened to also cover the configured low/high-red thresholds if the actual readings never
    // reach them: otherwise a visible window computed purely from the data (e.g. resting-range
    // readings that never dip toward pulseLowRed) sits entirely above/below a threshold band,
    // which collapses that band to zero height once clamped to the visible window (see
    // ThresholdBands.kt's clampToVisible) — i.e. the low bands silently never render just
    // because this user's pulse happens to stay comfortably inside the normal range. Only applied
    // at the default (not zoomed-in) view — see the matching comment in Spo2ChartContent for why.
    val minY = minPulse?.let {
        floorToMultipleOf5(if (zoomedIn) it else minOf(it, thresholdConfig.pulseLowRed))
    }
    val maxY = maxPulse?.let {
        ceilToMultipleOf5(if (zoomedIn) it else maxOf(it, thresholdConfig.pulseHighRed))
    }
    val bands = rememberPulseThresholdBands(thresholdConfig, visibleMinY = minY?.toDouble(), visibleMaxY = maxY?.toDouble())
    val rangeProvider = remember(minY, maxY) { fixedYRange(minY, maxY) }
    val yAxisItemPlacer = rememberYAxisItemPlacer(minY, maxY)
    val pulseLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(pulseColor)),
        stroke = CHART_LINE_STROKE,
    )
    val labelFontSize = MaterialTheme.typography.labelSmall.fontSize

    Column {
        Text("Pulse (bpm)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ProvideVicoTheme(rememberM3VicoTheme()) {
            DragToZoomOverlay(
                readings = readings,
                onRangeSelected = onRangeSelected,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            ) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            lineProvider = LineCartesianLayer.LineProvider.series(pulseLine),
                            rangeProvider = rangeProvider,
                        ),
                        startAxis = VerticalAxis.rememberStart(
                            line = rememberAxisLineComponent(fill = Fill(pulseColor)),
                            label = rememberAxisLabelComponent(
                                style = TextStyle(color = pulseColor, fontSize = labelFontSize),
                            ),
                            itemPlacer = yAxisItemPlacer,
                        ),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            label = rememberTimeAxisLabelComponent(),
                            valueFormatter = timeAxisFormatter,
                            itemPlacer = timeAxisItemPlacer,
                        ),
                        decorations = bands,
                    ),
                    modelProducer = modelProducer,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
