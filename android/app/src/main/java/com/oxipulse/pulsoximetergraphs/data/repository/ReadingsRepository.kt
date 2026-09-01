package com.oxipulse.pulsoximetergraphs.data.repository

import com.oxipulse.pulsoximetergraphs.data.csv.CsvParser
import com.oxipulse.pulsoximetergraphs.data.db.ReadingDao
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.db.Spo2EventCounter
import com.oxipulse.pulsoximetergraphs.data.db.ValueCount
import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The single funnel both import paths (SAF file import, BLE reassembly) and the UI go through:
 * [importCsv] parses+inserts, [observeCountInRange]/[plottedReadings]/[statsForRange] read back
 * for display.
 *
 * The read side is deliberately built so that none of these three ever has to materialize more
 * than a few hundred [ReadingEntity] objects at once, no matter how wide a range the user selects
 * — at 1 reading/sec (see PROTOCOL.md), a single continuously-worn year is on the order of tens
 * of millions of rows, and this app has no upper bound on how much history a user can accumulate.
 * A naive `SELECT * FROM readings WHERE range` would load the *entire* selected range as one
 * in-memory `List` before anything else could happen — fine for "last day", a real risk of
 * GC thrashing, jank, or an outright OOM for "last year" or a multi-year custom range. See each
 * function's own doc for exactly how it stays bounded.
 */
class ReadingsRepository(private val readingDao: ReadingDao) {

    /**
     * Parses [csvText] with [CsvParser], drops any row whose timestamp is already in the
     * database, and inserts the rest. Returns [CsvParser.ParseResult] with [readings][CsvParser
     * .ParseResult.readings] narrowed down to only what was actually newly inserted — so callers
     * (e.g. [com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient]) report "N rows synced" for
     * rows genuinely new to this database, not the size of whatever CSV happened to arrive.
     * [skippedRowCount][CsvParser.ParseResult.skippedRowCount]/[totalDataRowCount][CsvParser
     * .ParseResult.totalDataRowCount] are untouched — they describe the raw CSV's own parse
     * quality (malformed rows), a separate concern from which *valid* rows were already known.
     *
     * The explicit pre-check (rather than just letting [ReadingDao.insertAll]'s
     * REPLACE-on-conflict silently overwrite duplicates, which would still leave the database
     * itself correct) matters because every BLE sync now re-sends the ESP32's *entire* stored
     * history on every `REQUEST_DATA` (see PROTOCOL.md) rather than just what changed since the
     * last sync — without this, "rows synced" would report the device's whole history's size on
     * every single sync instead of what's actually new since last time.
     *
     * Callers must still only proceed (e.g. write CLEAR_BUFFER) once this suspend call returns.
     */
    suspend fun importCsv(csvText: String): CsvParser.ParseResult {
        // CsvParser.parse is plain CPU-bound work (regex split + date/time parsing per row), so
        // on Dispatchers.Main.immediate (the default for callers using viewModelScope) a large
        // file would otherwise freeze the UI for the whole parse instead of just showing a
        // progress state.
        val result = withContext(Dispatchers.Default) { CsvParser.parse(csvText) }
        if (result.readings.isEmpty()) {
            return result
        }
        val newReadings = withContext(Dispatchers.Default) {
            val existing = readingDao.existingTimestampsInRange(
                result.readings.minOf { it.timestampEpochSec },
                result.readings.maxOf { it.timestampEpochSec },
            ).toHashSet()
            result.readings.filterNot { it.timestampEpochSec in existing }
        }
        if (newReadings.isNotEmpty()) {
            readingDao.insertAll(newReadings)
        }
        return result.copy(readings = newReadings)
    }

    /** See [ReadingDao.observeCountInRange]'s own doc. */
    fun observeCountInRange(range: ClosedRange<Instant>): Flow<Int> =
        readingDao.observeCountInRange(range.start.epochSecond, range.endInclusive.epochSecond)

    /**
     * Chart-ready readings for [range], capped at [MAX_PLOTTED_POINTS] regardless of how many raw
     * rows the range actually contains. [totalCount] must be the range's current row count (from
     * [observeCountInRange]) — passed in rather than queried again here so the caller's own
     * reactive count and this fetch never disagree about which path to take.
     *
     * When [totalCount] is already at or under the cap, the exact rows are returned as-is — no
     * decimation needed or wanted; every real reading shows up.
     *
     * Otherwise, [range] is split into [MAX_PLOTTED_POINTS] / 4 equal-*duration* time buckets —
     * not equal-row-count buckets, deliberately: dividing by time keeps decimated points roughly
     * evenly spaced across the chart's x-axis even when sampling density varies (e.g. gaps where
     * the device wasn't worn), rather than clumping wherever data happens to be denser. For each
     * bucket, the readings holding that bucket's min/max SpO2 and min/max pulse are kept, so a
     * brief desaturation or pulse spike still shows up on the chart instead of being averaged
     * away — the same guarantee a prior Kotlin-side "decimate after loading everything" approach
     * gave, just computed as SQL aggregate queries ([ReadingDao.bucketedMinSpo2] and its three
     * siblings) instead of after materializing every row in the range as a [ReadingEntity]. This
     * is what actually keeps a multi-year range from having to hold millions of those in memory
     * just to render a 500-point chart. The four per-bucket queries can return the same row for
     * more than one extreme (e.g. one reading is both its bucket's min and max pulse, in a
     * near-flat bucket).
     *
     * [ReadingDao.firstInRange]/[ReadingDao.lastInRange] are always folded in too (deduplicated
     * along with everything else below), so the *first* and *last* plotted points are always the
     * exact first/last reading actually in [range] — not just whichever reading happened to hold
     * an extreme within the first/last time bucket. Without this, the chart's own visible x-axis
     * could start or end noticeably later/earlier than the selected range itself: e.g. selecting
     * 01:00–09:00 but the bucket containing 01:00 has its SpO2/pulse extremes at 01:19, so 01:19
     * — not 01:00 — would be the leftmost thing actually drawn (and labeled) on the chart, reading
     * as the chart's own timespan disagreeing with what was just selected.
     */
    suspend fun plottedReadings(range: ClosedRange<Instant>, totalCount: Int): List<ReadingEntity> {
        val startEpochSec = range.start.epochSecond
        val endEpochSec = range.endInclusive.epochSecond
        if (totalCount <= MAX_PLOTTED_POINTS) {
            return readingDao.rangeOrderedList(startEpochSec, endEpochSec)
        }
        val bucketCount = (MAX_PLOTTED_POINTS / 4).coerceAtLeast(1)
        val spanSeconds = (endEpochSec - startEpochSec + 1).coerceAtLeast(1)
        val extremes = readingDao.bucketedMinSpo2(startEpochSec, endEpochSec, bucketCount, spanSeconds) +
            readingDao.bucketedMaxSpo2(startEpochSec, endEpochSec, bucketCount, spanSeconds) +
            readingDao.bucketedMinPulse(startEpochSec, endEpochSec, bucketCount, spanSeconds) +
            readingDao.bucketedMaxPulse(startEpochSec, endEpochSec, bucketCount, spanSeconds)
        val boundary = listOfNotNull(
            readingDao.firstInRange(startEpochSec, endEpochSec),
            readingDao.lastInRange(startEpochSec, endEpochSec),
        )
        return (extremes + boundary).distinctBy { it.timestampEpochSec }.sortedBy { it.timestampEpochSec }
    }

    /**
     * Aggregate stats for [range] against [spo2EventThreshold] — see [ReadingStats]'s own doc for
     * the field-by-field split of what's computed where. Every field reflects the *entire* range,
     * however wide, while staying bounded in memory:
     * - min/max/avg: a single SQL aggregate query ([ReadingDao.statsForRange]) — SQLite computes
     *   these while streaming through the range, never materializing a row as a Kotlin object.
     * - p95: a (value, count) histogram ([ReadingDao.spo2Histogram]/[ReadingDao.pulseHistogram])
     *   instead of sorting every raw reading — bounded by the number of *distinct* SpO2/pulse
     *   values in range (SpO2 is a percentage, so at most 101; pulse only a little wider), not
     *   the row count. See [percentile95FromHistogram].
     * - the desaturation-event count/rate: streamed page by page through [Spo2EventCounter] (see
     *   [countSpo2EventsInRange]) rather than loaded as one `List` — this one genuinely has to
     *   visit every row (the merge-gap logic depends on exact row-to-row adjacency and timing,
     *   not just the multiset of values), but streaming keeps peak memory to one page at a time
     *   instead of the whole range.
     *
     * The events/hour rate is divided by how long [range]'s *data* actually spans — [firstInRange]
     * to [lastInRange] — not by [range]'s own width. A user can select a wider span than the
     * device has data for (e.g. an overnight range picked as 23:00–00:00 whose readings only
     * actually run 01:00–09:00 in the middle); dividing by the selected range's 25 hours there
     * would understate the true rate by a factor of ~3 compared to dividing by the 8 hours data
     * was actually recorded across.
     */
    suspend fun statsForRange(range: ClosedRange<Instant>, spo2EventThreshold: Int): ReadingStats {
        val startEpochSec = range.start.epochSecond
        val endEpochSec = range.endInclusive.epochSecond
        val base = readingDao.statsForRange(startEpochSec, endEpochSec)
        val eventCount = countSpo2EventsInRange(range, spo2EventThreshold)
        val firstReading = readingDao.firstInRange(startEpochSec, endEpochSec)
        val lastReading = readingDao.lastInRange(startEpochSec, endEpochSec)
        // Hours, not seconds/minutes: the whole point of this rate is to read naturally next to
        // "events" as a per-hour figure (e.g. "24 events over 3 hours of actual data" -> "8/hr")
        // regardless of how long the selected range happens to be, rather than a raw count that
        // means something different depending on how much of the range actually has data.
        val dataDurationHours = if (firstReading != null && lastReading != null) {
            (lastReading.timestampEpochSec - firstReading.timestampEpochSec) / 3_600.0
        } else {
            null
        }
        return base.copy(
            p95Spo2 = percentile95FromHistogram(readingDao.spo2Histogram(startEpochSec, endEpochSec)),
            p95Pulse = percentile95FromHistogram(readingDao.pulseHistogram(startEpochSec, endEpochSec)),
            spo2EventCount = eventCount,
            spo2EventsPerHour = eventCount?.let { count ->
                if (dataDurationHours != null && dataDurationHours > 0) count / dataDurationHours else null
            },
        )
    }

    /**
     * Streams [range] through [ReadingDao.pageInRange] in [EVENT_COUNT_PAGE_SIZE]-row pages,
     * feeding each page into one running [Spo2EventCounter], instead of loading the whole range
     * into a single `List` first — peak memory is bounded to one page, not the range's full row
     * count, which is what actually makes this safe to run over a multi-year range. Keyset
     * pagination (each page's `WHERE` starts at the previous page's last timestamp + 1), not
     * `OFFSET`/`LIMIT`, keeps every page an equally cheap index seek regardless of how far into
     * the range it starts.
     */
    private suspend fun countSpo2EventsInRange(range: ClosedRange<Instant>, threshold: Int): Int? {
        val counter = Spo2EventCounter(threshold)
        val endEpochSec = range.endInclusive.epochSecond
        var cursor = range.start.epochSecond
        while (true) {
            val page = readingDao.pageInRange(cursor, endEpochSec, EVENT_COUNT_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach(counter::accept)
            if (page.size < EVENT_COUNT_PAGE_SIZE) break
            cursor = page.last().timestampEpochSec + 1
        }
        return counter.result()
    }

    internal companion object {
        // Vico (the charting library) doesn't cull off-screen points, so line-rendering cost (and
        // pan/zoom smoothness) scales with point count regardless of how zoomed in the user
        // currently is — a big CSV import or a wide, densely-sampled date range could otherwise
        // mean tracing millions of points on every frame.
        // internal, not private: ReadingsRepositoryTest exercises the page-boundary behavior of
        // countSpo2EventsInRange directly, which needs this exact value to build a dataset that
        // spans more than one page without hardcoding it a second time.
        internal const val MAX_PLOTTED_POINTS = 500

        // Large enough that a typical session (a night's sleep at 1 reading/sec is ~30,000 rows)
        // finishes in a single page; small enough that even a years-wide range never holds more
        // than a few hundred KB of ReadingEntity objects in memory at once.
        internal const val EVENT_COUNT_PAGE_SIZE = 20_000
    }
}

/**
 * Nearest-rank 95th percentile (not interpolated, so the result is always one of the actual
 * readings — consistent with min/max, which are likewise real observed values), computed from a
 * (value, count) histogram rather than sorting every raw reading — see [ReadingDao.spo2Histogram]'s
 * own doc for why. [histogram] must already be sorted ascending by [ValueCount.value] (both DAO
 * histogram queries `ORDER BY ... ASC`).
 */
private fun percentile95FromHistogram(histogram: List<ValueCount>): Int? {
    val total = histogram.sumOf { it.count }
    if (total == 0) return null
    val rank = ceil(total * 0.95).toInt().coerceIn(1, total)
    var cumulative = 0
    for (bucket in histogram) {
        cumulative += bucket.count
        if (cumulative >= rank) return bucket.value
    }
    return histogram.last().value // Unreachable given the coerceIn above; defensive fallback only.
}
