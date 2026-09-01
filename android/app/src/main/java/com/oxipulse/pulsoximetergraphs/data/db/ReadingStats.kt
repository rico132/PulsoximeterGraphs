package com.oxipulse.pulsoximetergraphs.data.db

/**
 * Aggregate stats for a time range. Most fields are null when the range contains no rows
 * (SQL MIN/MAX/AVG over zero rows yield NULL, not zero — deliberately not coerced to 0 here,
 * since "0" would misleadingly look like a real reading).
 *
 * See [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.statsForRange]'s own
 * doc for exactly how each field is computed and kept bounded in memory even across a range
 * spanning millions of rows: [minSpo2]/[maxSpo2]/[avgSpo2]/[minPulse]/[maxPulse]/[avgPulse] come
 * from a single SQL aggregate query ([ReadingDao.statsForRange]); [p95Spo2]/[p95Pulse] from a
 * (value, count) histogram, not a full sort; [spo2EventCount]/[spo2EventsPerHour] by streaming
 * the range page by page through [Spo2EventCounter] rather than loading it as one `List`.
 */
data class ReadingStats(
    val minSpo2: Int?,
    val maxSpo2: Int?,
    val avgSpo2: Double?,
    val p95Spo2: Int? = null,
    // Number of separate desaturation events (contiguous runs of SpO2 below
    // ThresholdConfig.spo2EventThreshold) — see Spo2EventCounter's own doc for the exact
    // definition. Null (like the fields above), not 0, when there's no data at all for the range
    // — 0 is a legitimate "no desaturation events occurred" answer once there IS data, so it
    // must stay distinguishable from "we don't know."
    val spo2EventCount: Int? = null,
    // Rate, not the raw count above: spo2EventCount alone conflates "1 event in a 30-minute
    // range" with "1 event in a week" as if they meant the same thing. Computed as spo2EventCount
    // divided by the selected range's duration in hours, so it recomputes automatically whenever
    // either the range or the event count changes. Null under the same rule as every other field
    // here (no data, or a zero-length range).
    val spo2EventsPerHour: Double? = null,
    val minPulse: Int?,
    val maxPulse: Int?,
    val avgPulse: Double?,
    val p95Pulse: Int? = null,
)
