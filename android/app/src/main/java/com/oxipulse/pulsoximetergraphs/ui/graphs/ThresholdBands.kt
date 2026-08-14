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
 * needs. Physiological bounds (0-100% for SpO2, 0-200bpm for pulse) stand in for "unbounded"
 * ends so the danger/caution zones read as filling to the edge of plausible readings.
 *
 * Both metrics share one chart now (see GraphScreen's CombinedChartCard), each against its own
 * Y-axis — [verticalAxisPosition] on each band ties its Y-range to the correct one (SpO2 -> End,
 * Pulse -> Start) so a band drawn for one metric's scale doesn't get interpreted against the
 * other's.
 *
 * Callers key `remember`/recomposition on [config] (a [kotlinx.coroutines.flow.StateFlow]
 * collected as state upstream), so edits made in Settings show up on the graph immediately.
 */
private const val SPO2_FLOOR = 0.0
private const val SPO2_CEILING = 100.0
private const val PULSE_FLOOR = 0.0
private const val PULSE_CEILING = 220.0

@Composable
fun rememberSpo2ThresholdBands(config: ThresholdConfig): List<HorizontalBox> =
    remember(config) {
        listOf(
            HorizontalBox(
                y = { config.spo2Red.toDouble()..config.spo2Orange.toDouble() },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
            HorizontalBox(
                y = { SPO2_FLOOR..config.spo2Red.toDouble() },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
        )
    }

@Composable
fun rememberPulseThresholdBands(config: ThresholdConfig): List<HorizontalBox> =
    remember(config) {
        listOf(
            HorizontalBox(
                y = { config.pulseLowRed.toDouble()..config.pulseLowOrange.toDouble() },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { PULSE_FLOOR..config.pulseLowRed.toDouble() },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { config.pulseHighOrange.toDouble()..config.pulseHighRed.toDouble() },
                box = ShapeComponent(fill = Fill(StatusWarning.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            HorizontalBox(
                y = { config.pulseHighRed.toDouble()..PULSE_CEILING },
                box = ShapeComponent(fill = Fill(StatusCritical.copy(alpha = BAND_ALPHA))),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
        )
    }

// Bumped from the previous 0.18: at that alpha, blending even a correctly saturated fixed red
// over the chart's near-black dark background left too little of the source hue to read
// unambiguously as red rather than a dark, muddy brown. Still translucent enough to see the
// gridlines and data line through it.
private const val BAND_ALPHA = 0.30f
