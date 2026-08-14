package com.oxipulse.pulsoximetergraphs.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Chart series identity colors (SpO2 / Pulse lines + their matching axis). Deliberately NOT
// this app's Material primary/tertiary roles (Purple/Pink): running those through the dataviz
// palette validator (validate_palette.js) as a 2-slot categorical pair measures a normal-vision
// ΔE of ~12 in both light and dark — under the 15 floor for telling two series apart, i.e. they
// measurably don't read as distinct colors. These four instead come from the skill's validated
// default categorical palette (slot 1 blue, slot 3 aqua), which clears every check (worst
// adjacent ΔE 24.0 light / 20.9 dark) — chosen over the palette's own slot-2 orange specifically
// so neither line's color is easily confused with the orange/red threshold bands drawn behind
// it (see StatusWarning/StatusCritical below). Like the rest of the categorical palette, these
// DO step per mode (unlike the status colors) — see palette.md.
val ChartSpo2Light = Color(0xFF2A78D6)
val ChartSpo2Dark = Color(0xFF3987E5)
val ChartPulseLight = Color(0xFF1BAF7A)
val ChartPulseDark = Color(0xFF199E70)

// Threshold-band status colors (SpO2/pulse danger zones). Fixed across light/dark — NOT derived
// from MaterialTheme.colorScheme.error or any other theme role — per the dataviz skill's status
// rule: "status never follows the theme." A translucent band still ends up looking somewhat
// different once composited over the chart's light vs. dark background (unavoidable with any
// non-opaque fill), but previously the *source* color itself also changed per theme (M3's dark
// "error" role is a desaturated pink-red, not a saturated red) on top of that, which is what
// actually produced a band that read as yellowish-orange instead of red in dark mode. Fixing the
// source hue removes that compounding: what's left is only the unavoidable background-blend
// difference, not a theme-driven hue shift. Values are the skill's documented default status
// steps (each already validated ≥3:1 against both a light and a dark chart surface).
val StatusWarning = Color(0xFFFAB219)
val StatusCritical = Color(0xFFD03B3B)
