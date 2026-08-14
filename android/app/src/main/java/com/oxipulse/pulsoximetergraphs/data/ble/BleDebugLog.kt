package com.oxipulse.pulsoximetergraphs.data.ble

import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mirror of [BleGattClient]'s diagnostic log lines, surfaced on the Settings screen so
 * a BLE sync can be diagnosed by copy-pasting straight out of the app -- for whoever's testing
 * without adb/logcat available. Deliberately process-lifetime only (never written to disk):
 * this is a debugging aid, not a feature, and clearing on process death avoids it silently
 * growing into a second, unmanaged log store.
 */
object BleDebugLog {
    private const val MAX_ENTRIES = 500

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entriesList = ArrayDeque<String>()
    private val _entries = MutableStateFlow("")
    val entries: StateFlow<String> = _entries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        entriesList.addLast("${timestampFormat.format(System.currentTimeMillis())}  $message")
        while (entriesList.size > MAX_ENTRIES) entriesList.removeFirst()
        _entries.value = entriesList.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        entriesList.clear()
        _entries.value = ""
    }
}
