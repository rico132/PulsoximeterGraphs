package com.aternos.pulsoximetergraphs.ui.graphs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.aternos.pulsoximetergraphs.data.settings.ThresholdConfig
import com.aternos.pulsoximetergraphs.ui.theme.extendedColors
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent

/**
 * Maps [ThresholdConfig] -> Vico [HorizontalBox] decorations: a shaded region between two
 * Y-values, drawn behind the data line — exactly the "threshold zone" primitive this screen
 * needs. Physiological bounds (0-100% for SpO2, 0-200bpm for pulse) stand in for "unbounded"
 * ends so the danger/caution zones read as filling to the edge of plausible readings.
 *
 * Callers key `remember`/recomposition on [config] (a [kotlinx.coroutines.flow.StateFlow]
 * collected as state upstream), so edits made in Settings show up on the graph immediately.
 */
private const val SPO2_FLOOR = 0.0
private const val SPO2_CEILING = 100.0
private const val PULSE_FLOOR = 0.0
private const val PULSE_CEILING = 220.0

@Composable
fun rememberSpo2ThresholdBands(config: ThresholdConfig): List<HorizontalBox> {
    val orange = MaterialTheme.extendedColors.orange
    val red = MaterialTheme.colorScheme.error
    return remember(config, orange, red) {
        listOf(
            HorizontalBox(
                y = { config.spo2Red.toDouble()..config.spo2Orange.toDouble() },
                box = ShapeComponent(fill = Fill(orange.copy(alpha = BAND_ALPHA))),
            ),
            HorizontalBox(
                y = { SPO2_FLOOR..config.spo2Red.toDouble() },
                box = ShapeComponent(fill = Fill(red.copy(alpha = BAND_ALPHA))),
            ),
        )
    }
}

@Composable
fun rememberPulseThresholdBands(config: ThresholdConfig): List<HorizontalBox> {
    val orange = MaterialTheme.extendedColors.orange
    val red = MaterialTheme.colorScheme.error
    return remember(config, orange, red) {
        listOf(
            HorizontalBox(
                y = { config.pulseLowRed.toDouble()..config.pulseLowOrange.toDouble() },
                box = ShapeComponent(fill = Fill(orange.copy(alpha = BAND_ALPHA))),
            ),
            HorizontalBox(
                y = { PULSE_FLOOR..config.pulseLowRed.toDouble() },
                box = ShapeComponent(fill = Fill(red.copy(alpha = BAND_ALPHA))),
            ),
            HorizontalBox(
                y = { config.pulseHighOrange.toDouble()..config.pulseHighRed.toDouble() },
                box = ShapeComponent(fill = Fill(orange.copy(alpha = BAND_ALPHA))),
            ),
            HorizontalBox(
                y = { config.pulseHighRed.toDouble()..PULSE_CEILING },
                box = ShapeComponent(fill = Fill(red.copy(alpha = BAND_ALPHA))),
            ),
        )
    }
}

private const val BAND_ALPHA = 0.18f
