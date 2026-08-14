package com.oxipulse.pulsoximetergraphs.data.db

/**
 * Aggregate stats for a time range. Most fields are null when the range contains no rows
 * (SQL MIN/MAX/AVG over zero rows yield NULL, not zero — deliberately not coerced to 0 here,
 * since "0" would misleadingly look like a real reading).
 *
 * [p95Spo2]/[p95Pulse] are the 95th percentile (nearest-rank, not interpolated -- always one of
 * the actual readings, consistent with min/max) and are computed in Kotlin from the same
 * in-memory reading list the UI already holds for the chart (see GraphViewModel.stats), not by
 * this class's own SQL query below -- percentile support isn't reliably available across the
 * range of SQLite versions bundled with this app's supported Android versions.
 */
data class ReadingStats(
    val minSpo2: Int?,
    val maxSpo2: Int?,
    val avgSpo2: Double?,
    val p95Spo2: Int? = null,
    val minPulse: Int?,
    val maxPulse: Int?,
    val avgPulse: Double?,
    val p95Pulse: Int? = null,
)
