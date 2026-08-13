package com.aternos.pulsoximetergraphs.data.settings

import kotlinx.serialization.Serializable

/**
 * Threshold config for chart highlighting. Defaults mirror PROTOCOL.md's
 * `assets/default_thresholds.json` verbatim.
 *
 * Ordering invariant (enforced by [validate], per PROTOCOL.md): chart rendering assumes
 * ordered thresholds, so an invalid config must never be persisted.
 * `pulseLowRed < pulseLowOrange < pulseHighOrange < pulseHighRed` and `spo2Red < spo2Orange`.
 */
@Serializable
data class ThresholdConfig(
    val spo2Orange: Int = 95,
    val spo2Red: Int = 90,
    val pulseLowOrange: Int = 50,
    val pulseLowRed: Int = 45,
    val pulseHighOrange: Int = 100,
    val pulseHighRed: Int = 120,
) {
    /** Returns a human-readable validation error, or null if [this] is valid. */
    fun validate(): String? = when {
        spo2Red >= spo2Orange -> "SpO2 red threshold must be lower than orange"
        pulseLowRed >= pulseLowOrange -> "Pulse low-red must be lower than low-orange"
        pulseLowOrange >= pulseHighOrange -> "Pulse low-orange must be lower than high-orange"
        pulseHighOrange >= pulseHighRed -> "Pulse high-orange must be lower than high-red"
        else -> null
    }

    fun isValid(): Boolean = validate() == null

    companion object {
        val DEFAULT = ThresholdConfig()
    }
}
