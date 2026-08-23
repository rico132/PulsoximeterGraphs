package com.oxipulse.pulsoximetergraphs.data.db

/**
 * Aggregate stats for a time range. Most fields are null when the range contains no rows
 * (SQL MIN/MAX/AVG over zero rows yield NULL, not zero — deliberately not coerced to 0 here,
 * since "0" would misleadingly look like a real reading).
 *
 * [p95Spo2]/[p95Pulse]/[spo2EventCount] are all computed in Kotlin from the same in-memory
 * reading list the UI already holds for the chart (see GraphViewModel.stats), not by this
 * class's own SQL query below -- percentile support isn't reliably available across the range of
 * SQLite versions bundled with this app's supported Android versions, and desaturation-event
 * counting needs to walk the readings in order, which SQL's aggregate functions can't do.
 */
data class ReadingStats(
    val minSpo2: Int?,
    val maxSpo2: Int?,
    val avgSpo2: Double?,
    val p95Spo2: Int? = null,
    // Number of separate desaturation events (contiguous runs of SpO2 below
    // SPO2_EVENT_THRESHOLD_PERCENT) — see GraphViewModel.countSpo2Events's own doc for the exact
    // definition. Null (like the fields above), not 0, when there's no data at all for the range
    // — 0 is a legitimate "no desaturation events occurred" answer once there IS data, so it
    // must stay distinguishable from "we don't know."
    val spo2EventCount: Int? = null,
    val minPulse: Int?,
    val maxPulse: Int?,
    val avgPulse: Double?,
    val p95Pulse: Int? = null,
)
