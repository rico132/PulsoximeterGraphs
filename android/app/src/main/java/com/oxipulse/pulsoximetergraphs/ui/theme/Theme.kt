package com.oxipulse.pulsoximetergraphs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra color roles M3's default [MaterialTheme.colorScheme] doesn't cover — specifically the
 * two chart-series identity colors (SpO2/Pulse), which are deliberately NOT this app's
 * primary/tertiary roles (see Color.kt for why) but still need to adapt per light/dark mode,
 * unlike the fixed status colors used for threshold bands.
 */
data class ExtendedColors(
    val chartSpo2: Color,
    val chartPulse: Color,
)

private val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(chartSpo2 = ChartSpo2Light, chartPulse = ChartPulseLight)
}

/** Access via `MaterialTheme.extendedColors.chartPulse` / `.chartSpo2` (see the property below). */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

@Composable
fun PulsoximeterGraphsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = ExtendedColors(
        chartSpo2 = if (darkTheme) ChartSpo2Dark else ChartSpo2Light,
        chartPulse = if (darkTheme) ChartPulseDark else ChartPulseLight,
    )

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
