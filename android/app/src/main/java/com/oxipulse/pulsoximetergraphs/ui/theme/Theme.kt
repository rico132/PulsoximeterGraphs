package com.oxipulse.pulsoximetergraphs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Extra color roles M3's default [MaterialTheme.colorScheme] doesn't cover. */
data class ExtendedColors(
    val orange: Color,
)

private val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(orange = OrangeLight)
}

/** Access via `MaterialTheme.extendedColors.orange` (see the extension property below). */
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
    val extendedColors = ExtendedColors(orange = if (darkTheme) OrangeDark else OrangeLight)

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
