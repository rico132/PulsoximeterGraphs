package com.aternos.pulsoximetergraphs.data.db

/**
 * Aggregate stats for a time range. All fields are null when the range contains no rows
 * (SQL MIN/MAX/AVG over zero rows yield NULL, not zero — deliberately not coerced to 0 here,
 * since "0" would misleadingly look like a real reading).
 */
data class ReadingStats(
    val minSpo2: Int?,
    val maxSpo2: Int?,
    val avgSpo2: Double?,
    val minPulse: Int?,
    val maxPulse: Int?,
    val avgPulse: Double?,
)
