package com.oxipulse.pulsoximetergraphs.data.settings

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [ThresholdConfig] to `filesDir/thresholds.json`, seeding it from the bundled
 * `assets/default_thresholds.json` on first run. Exposes the current config as a
 * [StateFlow] so chart band decorations (see ui/graphs/ThresholdBands.kt) recompute the
 * moment a Settings edit is saved.
 */
class ThresholdsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val thresholdsFile: File = File(context.filesDir, "thresholds.json")

    private val _config = MutableStateFlow(loadOrSeed())
    val config: StateFlow<ThresholdConfig> = _config.asStateFlow()

    /** Returns null on success, or a validation-error message if [newConfig] is invalid. */
    fun update(newConfig: ThresholdConfig): String? {
        val error = newConfig.validate()
        if (error != null) return error

        writeAtomically(newConfig)
        _config.value = newConfig
        return null
    }

    private fun loadOrSeed(): ThresholdConfig {
        if (!thresholdsFile.exists()) {
            val defaults = readBundledDefaults()
            writeAtomically(defaults)
            return defaults
        }
        return try {
            val config = json.decodeFromString(ThresholdConfig.serializer(), thresholdsFile.readText())
            if (config.isValid()) config else ThresholdConfig.DEFAULT
        } catch (e: Exception) {
            ThresholdConfig.DEFAULT
        }
    }

    private fun readBundledDefaults(): ThresholdConfig = try {
        val text = context.assets.open("default_thresholds.json").bufferedReader().use { it.readText() }
        val config = json.decodeFromString(ThresholdConfig.serializer(), text)
        if (config.isValid()) config else ThresholdConfig.DEFAULT
    } catch (e: Exception) {
        ThresholdConfig.DEFAULT
    }

    /** Atomic write-then-rename so a crash mid-write can never leave a truncated config file. */
    private fun writeAtomically(config: ThresholdConfig) {
        val tempFile = File(context.filesDir, "thresholds.json.tmp")
        tempFile.writeText(json.encodeToString(config))
        if (!tempFile.renameTo(thresholdsFile)) {
            // renameTo can fail across some filesystems; fall back to a direct write.
            thresholdsFile.writeText(json.encodeToString(config))
            tempFile.delete()
        }
    }
}
