package com.oxipulse.pulsoximetergraphs.ui.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import com.oxipulse.pulsoximetergraphs.ui.theme.StatusCritical
import com.oxipulse.pulsoximetergraphs.ui.theme.StatusWarning
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent

/**
 * Maps [ThresholdConfig] -> Vico [HorizontalBox] decorations: a shaded region between two
 * Y-values, drawn behind the data line — exactly the "threshold zone" primitive this screen
 * needs.
 *
 * Both metrics share one chart now (see GraphScreen's CombinedChartCard), each against its own
 * Y-axis — [verticalAxisPosition] on each band ties its Y-range to the correct one (SpO2 -> End,
 * Pulse -> Start) so a band drawn for one metric's scale doesn't get interpreted against the
 * other's.
 *
 * [visibleMinY]/[visibleMaxY] are the *same* rounded bounds passed to that axis's
 * [com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider] (null when
 * there's no data and the axis is auto-scaling instead, in which case the physiological
 * floor/ceiling below is used as a stand-in since the actual visible range isn't known). Each
 * band clamps its Y-range to this window before drawing: a band's semantic range (e.g. "0 to
 * spo2Red") is usually far wider than what's actually on-screen (real readings rarely dip to 0%
 * or 220bpm), so without clamping, Vico computes a pixel position for the un-clamped end that
 * falls outside the chart's own plot bounds — and since decorations aren't clipped to that area,
 * the shaded rectangle bleeds past the chart, in practice as far as the card's edge.
 *
 * Callers key `remember`/recomposition on [config] (a [kotlinx.coroutines.flow.StateFlow]
 * collected as state upstream), so edits made in Settings show up on the graph immediately.
 */
private const val SPO2_FLOOR = 0.0
private const val SPO2_CEILING = 100.0
private const val PULSE_FLOOR = 0.0
private const val PULSE_CEILING = 220.0

/** Clamps both ends of [lo]..[hi] into [visibleMinY]..[visibleMaxY] — see the class doc above. */
private fun clampToVisible(lo: Double, hi: Double, visibleMinY: Double, visibleMaxY: Double): ClosedFloatingPointRange<Double> =
    lo.coerceIn(visibleMinY, visibleMaxY)..hi.coerceIn(visibleMinY, visibleMaxY)

@Composable
fun rememberSpo2ThresholdBands(config: ThresholdConfig, visibleMinY: Double?, visibleMaxY: Double?): List<HorizontalBox> {
    val minY = visibleMinY ?: SPO2_FLOOR
    val maxY = visibleMaxY ?: SPO2_CEILING
    return remember(config, minY, maxY) {
        listOf(
            HorizontalBox(
                y = { clampToVisible(config.spo2Red.toDouble(), config.spo2Orange.toDouble(), minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
            HorizontalBox(
                y = { clampToVisible(SPO2_FLOOR, config.spo2Red.toDouble(), minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
        )
    }
}

@Composable
fun rememberPulseThresholdBands(config: ThresholdConfig, visibleMinY: Double?, visibleMaxY: Double?): List<HorizontalBox> {
    val minY = visibleMinY ?: PULSE_FLOOR
    val maxY = visibleMaxY ?: PULSE_CEILING
    return remember(config, minY, maxY) {
        listOf(
            HorizontalBox(
                y = { clampToVisible(config.pulseLowRed.toDouble(), config.pulseLowOrange.toDouble(), minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { clampToVisible(PULSE_FLOOR, config.pulseLowRed.toDouble(), minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { clampToVisible(config.pulseHighOrange.toDouble(), config.pulseHighRed.toDouble(), minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { clampToVisible(config.pulseHighRed.toDouble(), PULSE_CEILING, minY, maxY) },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
        )
    }
}

// Bumped from the previous 0.18: at that alpha, blending even a correctly saturated fixed red
// over the chart's near-black dark background left too little of the source hue to read
// unambiguously as red rather than a dark, muddy brown. Still translucent enough to see the
// gridlines and data line through it.
private const val BAND_ALPHA = 0.30f
