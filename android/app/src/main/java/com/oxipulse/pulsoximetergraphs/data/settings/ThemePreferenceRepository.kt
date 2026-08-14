package com.oxipulse.pulsoximetergraphs.data.settings

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { LIGHT, DARK }

/**
 * Persists the user's dark/light toggle in SharedPreferences — this is a single boolean-ish
 * value, so it skips the JSON-file pattern [ThresholdsRepository] uses for its larger config
 * object in favor of the platform's own tool for exactly this ("a handful of small key-value
 * settings"). Seeded from the system's current setting on first run only; after that the
 * toggle is a deliberate, persisted override that no longer tracks the system setting even if
 * it changes.
 */
class ThemePreferenceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun toggle() {
        val next = if (_themeMode.value == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        prefs.edit().putBoolean(KEY_DARK, next == ThemeMode.DARK).apply()
        _themeMode.value = next
    }

    private fun loadThemeMode(): ThemeMode =
        if (prefs.getBoolean(KEY_DARK, systemIsDark())) ThemeMode.DARK else ThemeMode.LIGHT

    private fun systemIsDark(): Boolean {
        val uiMode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        const val PREFS_NAME = "theme_prefs"
        const val KEY_DARK = "dark_mode"
    }
}
