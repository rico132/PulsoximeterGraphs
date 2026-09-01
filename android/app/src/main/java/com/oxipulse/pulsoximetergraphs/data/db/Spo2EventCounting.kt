package com.oxipulse.pulsoximetergraphs.data.db

/**
 * A desaturation "event" is defined against a configured SpO2 percentage — see
 * [com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig.spo2EventThreshold] — and two
 * dips below it separated by a recovery shorter than this count as one continuous event, not two
 * — see [Spo2EventCounter]. A brief single-sample bounce back above threshold (sensor noise, a
 * momentary good reading mid-desaturation) shouldn't fragment one real episode into several.
 */
internal const val EVENT_MERGE_GAP_SECONDS = 5L

/**
 * Incremental desaturation-event counter: feed readings one at a time, in chronological order,
 * via [accept]; read the running total via [result] at any point. Kept as a stateful accumulator
 * rather than a plain function over a `List<ReadingEntity>` specifically so
 * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository] can stream a huge range
 * through it page by page (see [ReadingDao.pageInRange] and that repository's own
 * `countSpo2EventsInRange` doc) without ever holding more than one page of readings in memory at
 * once — a multi-year range at 1 reading/sec can be tens of millions of rows, far more than
 * should ever be materialized as one in-memory `List`.
 *
 * A maximal run of readings with SpO2 below [threshold] is one event, where a recovery above
 * threshold shorter than [EVENT_MERGE_GAP_SECONDS] doesn't end the event — the next dip merges
 * into the same one instead of starting a new one. E.g. SpO2 dipping to 90, recovering to 96 for
 * 2 seconds, then dipping to 92 again is 1 event; the same recovery lasting 10 seconds would be 2.
 * Readings must be [accept]ed in chronological order (see `ReadingDao`'s
 * `ORDER BY timestampEpochSec ASC`) — unlike a percentile, this genuinely depends on sequence
 * (and now timing), not just the multiset of SpO2 values.
 */
// internal, not private, so ReadingsEventCountingTest can exercise this directly — the merge-gap
// timing logic is exactly the kind of thing worth a real unit test rather than trusting by eye.
internal class Spo2EventCounter(private val threshold: Int) {
    private var eventCount = 0
    private var inEvent = false

    // Epoch second of the most recent below-threshold reading seen so far — once a run ends,
    // this is left holding that run's own last (i.e. most recent) below-threshold timestamp,
    // which is exactly what the next run's gap needs to be measured from.
    private var lastBelowThresholdEpochSec: Long? = null
    private var sawAnyReading = false

    fun accept(reading: ReadingEntity) {
        sawAnyReading = true
        val belowThreshold = reading.spo2 < threshold
        if (belowThreshold) {
            if (!inEvent) {
                val gapSeconds = lastBelowThresholdEpochSec?.let { reading.timestampEpochSec - it }
                if (gapSeconds == null || gapSeconds >= EVENT_MERGE_GAP_SECONDS) {
                    eventCount++
                }
                inEvent = true
            }
            lastBelowThresholdEpochSec = reading.timestampEpochSec
        } else {
            inEvent = false
        }
    }

    /**
     * Null (not 0) if [accept] was never called — no data at all, consistent with every other
     * field in [ReadingStats]; 0 is a legitimate "no desaturation events occurred" answer once
     * there IS data, so it must stay distinguishable from "we don't know."
     */
    fun result(): Int? = if (sawAnyReading) eventCount else null
}

/**
 * One-shot convenience over an already in-memory [List] — e.g. for tests, or any future caller
 * that already has a small, fully-loaded range on hand. Real production stats computation streams
 * through [Spo2EventCounter] directly instead (see its own doc for why); this is equivalent to
 * feeding the same [Spo2EventCounter] one [List] at a time.
 */
internal fun countSpo2Events(readings: List<ReadingEntity>, threshold: Int): Int? {
    val counter = Spo2EventCounter(threshold)
    readings.forEach(counter::accept)
    return counter.result()
}
