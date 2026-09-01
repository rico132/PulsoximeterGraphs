package com.oxipulse.pulsoximetergraphs.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's *desired* test-mode setting in SharedPreferences — a single boolean, same
 * pattern as [ThemePreferenceRepository]. Deliberately NOT a live mirror of the ESP32's own
 * test-mode flag: PROTOCOL.md's Status characteristic has no read-back for it (stretch/not-MVP),
 * so there is no way to ask the device what it currently has stored. Instead this is "what the
 * app should push to the device next time it connects" — [BleGattClient][com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient]'s
 * own sync writes SET_TEST_MODE with this value as part of every sync, before REQUEST_DATA/
 * CLEAR_BUFFER, rather than a separate on-demand BLE connection just to flip a bit. That ties the
 * value CLEAR_BUFFER's deletion behavior depends on (see PROTOCOL.md — it deletes from the
 * PO-400, not the ESP32 itself) to the exact same connection, so a failed SET_TEST_MODE write
 * fails the whole sync via the existing retry logic instead of leaving CLEAR_BUFFER to run under
 * an unconfirmed assumption. Defaults to true (matches the ESP32's own PROTOCOL.md-documented
 * default) until the user changes it.
 */
class TestModePreferenceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _desiredTestMode = MutableStateFlow(prefs.getBoolean(KEY_TEST_MODE, true))
    val desiredTestMode: StateFlow<Boolean> = _desiredTestMode.asStateFlow()

    fun setDesiredTestMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TEST_MODE, enabled).apply()
        _desiredTestMode.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "test_mode_prefs"
        const val KEY_TEST_MODE = "desired_test_mode"
    }
}
