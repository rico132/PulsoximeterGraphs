package com.oxipulse.pulsoximetergraphs.ui.graphs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.di.AppContainer
import com.oxipulse.pulsoximetergraphs.ui.rangepicker.DateTimeRangePickerDialog
import com.oxipulse.pulsoximetergraphs.ui.theme.extendedColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
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
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    appContainer: AppContainer,
    onOpenSettings: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
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
    val canZoomOut by viewModel.canZoomOut.collectAsState()

    var showRangePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The activity-result callback runs on the main thread, and reading through a SAF
        // content-provider stream is a blocking IPC round trip — for anything but a tiny file
        // this froze the UI for a couple of seconds right after picking it. Do the read on IO.
        coroutineScope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text != null) {
                viewModel.importCsvText(text) { inserted, skipped ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Imported $inserted rows ($skipped skipped)")
                    }
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
                    if (canZoomOut) {
                        IconButton(onClick = { viewModel.zoomOut() }) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                        }
                    }
                    IconButton(onClick = { showRangePicker = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Select date range")
                    }
                    IconButton(onClick = { openDocumentLauncher.launch(arrayOf("text/csv", "*/*")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Import CSV")
                    }
                    IconButton(onClick = { viewModel.startBleSync() }) {
                        if (isSyncing(syncState)) {
                            BleSyncSpinner()
                        } else {
                            Icon(Icons.Filled.Bluetooth, contentDescription = "Sync via BLE")
                        }
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
        // Shared between both charts' bottom axes so they pick the exact same tick indices —
        // see the parameter doc on rememberSharedTimeAxisItemPlacer for why leaving each chart
        // to compute its own (Vico's default) caused SpO2 and Pulse to show different times for
        // what's supposed to be the same x-position.
        val timeAxisItemPlacer = rememberSharedTimeAxisItemPlacer(plottedReadings.size)

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            syncStatusText(syncState)?.let { SyncStatusRow(it) }
            RangeSummary(selectedRange)
            StatsPanel(stats)
            SpO2ChartCard(
                readings = plottedReadings,
                thresholdConfig = thresholdConfig,
                minSpo2 = stats.minSpo2,
                maxSpo2 = stats.maxSpo2,
                zoomState = sharedZoomState,
                scrollState = sharedScrollState,
                onRangeSelected = viewModel::setRange,
                timeAxisItemPlacer = timeAxisItemPlacer,
            )
            PulseChartCard(
                readings = plottedReadings,
                thresholdConfig = thresholdConfig,
                minPulse = stats.minPulse,
                maxPulse = stats.maxPulse,
                zoomState = sharedZoomState,
                scrollState = sharedScrollState,
                onRangeSelected = viewModel::setRange,
                timeAxisItemPlacer = timeAxisItemPlacer,
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
 * Human-readable label for whichever step of PROTOCOL.md's sync sequence is currently in
 * flight, or null once it's back to Idle/Success/Failed (those are surfaced via the snackbar
 * instead — see the LaunchedEffect(syncState) above). [BleGattClient.SyncState.ReceivingData]
 * carries a live byte count straight from the Data characteristic's notifications, so this
 * updates continuously while the transfer is running rather than sitting on one static label —
 * mirrors the per-chunk progress the sending side (tools/ble_csv_sender.py) prints.
 */
private fun syncStatusText(state: BleGattClient.SyncState): String? = when (state) {
    BleGattClient.SyncState.Scanning -> "Scanning for PulsoxRelay…"
    BleGattClient.SyncState.Connecting -> "Connecting…"
    BleGattClient.SyncState.RequestingData -> "Requesting data…"
    is BleGattClient.SyncState.ReceivingData -> "Receiving data… (${state.bytesReceived} bytes)"
    BleGattClient.SyncState.Inserting -> "Saving to database…"
    BleGattClient.SyncState.ClearingBuffer -> "Finishing up…"
    BleGattClient.SyncState.Idle,
    is BleGattClient.SyncState.Success,
    is BleGattClient.SyncState.Failed,
    -> null
}

@Composable
private fun SyncStatusRow(status: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BleSyncSpinner()
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A small rotating ring, sized to exactly match the 24dp icon it stands in for in the top app
 * bar's Bluetooth button. Material's default [androidx.compose.material3.CircularProgressIndicator]
 * ships at its own much larger default track/size and only had `height` constrained here (not
 * `width`), so it rendered oversized and lopsided next to the other icon buttons instead of
 * reading as "this icon is now busy."
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
            DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(ZoneId.systemDefault()))
        }
    }

private const val TARGET_TIME_AXIS_LABEL_COUNT = 6

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
private fun rememberSharedTimeAxisItemPlacer(pointCount: Int): HorizontalAxis.ItemPlacer =
    remember(pointCount) {
        val spacing = (pointCount / TARGET_TIME_AXIS_LABEL_COUNT).coerceAtLeast(1)
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
 * Y-axis item placer using [niceYAxisStep], or Vico's own default when there's no data for the
 * selected range (matches [fixedYRange]'s null-range fallback to auto-scaling).
 */
@Composable
private fun rememberYAxisItemPlacer(minY: Int?, maxY: Int?): VerticalAxis.ItemPlacer =
    remember(minY, maxY) {
        if (minY != null && maxY != null) {
            val step = niceYAxisStep(minY, maxY)
            VerticalAxis.ItemPlacer.step(step = { step })
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
 * SpO2 and Pulse are two separate cards, each with its own chart, scale, and threshold bands —
 * NOT one dual-axis chart. The thresholds for the two metrics visually overlap in places (e.g. a
 * pulse-high-orange band and an spo2-red band can land at the same on-screen height once each is
 * mapped onto its own axis), which reads as one metric's danger zone bleeding into the other's
 * when both are drawn on a shared plot — splitting them back into independent charts removes
 * that ambiguity even though the two share the horizontal (time) axis conceptually. `zoomState`/
 * `scrollState` are still shared between both cards purely because pinch/drag zoom is disabled on
 * both (see the comment on `sharedZoomState` in [GraphScreen]) — there's no live pan/zoom to keep
 * in sync, just a single state instance CartesianChartHost requires per chart.
 */
@Composable
private fun SpO2ChartCard(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
    minSpo2: Int?,
    maxSpo2: Int?,
    zoomState: VicoZoomState,
    scrollState: VicoScrollState,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    timeAxisItemPlacer: HorizontalAxis.ItemPlacer,
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
    // SpO2 is a percentage, so 100 is a hard ceiling regardless of the padding-to-5 rule.
    val minY = minSpo2?.let { floorToMultipleOf5(minOf(it, thresholdConfig.spo2Red)) }
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
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
}

@Composable
private fun PulseChartCard(
    readings: List<ReadingEntity>,
    thresholdConfig: ThresholdConfig,
    minPulse: Int?,
    maxPulse: Int?,
    zoomState: VicoZoomState,
    scrollState: VicoScrollState,
    onRangeSelected: (ClosedRange<Instant>) -> Unit,
    timeAxisItemPlacer: HorizontalAxis.ItemPlacer,
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
    // because this user's pulse happens to stay comfortably inside the normal range.
    val minY = minPulse?.let { floorToMultipleOf5(minOf(it, thresholdConfig.pulseLowRed)) }
    val maxY = maxPulse?.let { ceilToMultipleOf5(maxOf(it, thresholdConfig.pulseHighRed)) }
    val bands = rememberPulseThresholdBands(thresholdConfig, visibleMinY = minY?.toDouble(), visibleMaxY = maxY?.toDouble())
    val rangeProvider = remember(minY, maxY) { fixedYRange(minY, maxY) }
    val yAxisItemPlacer = rememberYAxisItemPlacer(minY, maxY)
    val pulseLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(pulseColor)),
        stroke = CHART_LINE_STROKE,
    )
    val labelFontSize = MaterialTheme.typography.labelSmall.fontSize

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
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
}
