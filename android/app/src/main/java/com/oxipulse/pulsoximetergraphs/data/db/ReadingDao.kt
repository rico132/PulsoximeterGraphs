package com.oxipulse.pulsoximetergraphs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    /** REPLACE gives free dedupe: re-importing the same second overwrites, never duplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<ReadingEntity>)

    /**
     * Reactive row count for a range — Room's own invalidation tracking re-emits this whenever
     * the `readings` table changes (a BLE sync, a CSV import), the same way a
     * `Flow<List<ReadingEntity>>` query would, but without ever materializing a single
     * [ReadingEntity]: `COUNT(*)` is answered from the index alone. Used both to decide, in
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings],
     * whether a range is small enough to return as-is or needs decimating, and as the shared
     * "something changed, refetch" trigger for that repository's other range queries — see its
     * own doc for why a plain unbounded `Flow<List<ReadingEntity>>` isn't used for this any more.
     */
    @Query("SELECT COUNT(*) FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec")
    fun observeCountInRange(startEpochSec: Long, endEpochSec: Long): Flow<Int>

    /**
     * Every row in the range, in order — only safe to call once the caller already knows (via
     * [observeCountInRange]) that the range is small; see
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings].
     */
    @Query(
        "SELECT * FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "ORDER BY timestampEpochSec ASC"
    )
    suspend fun rangeOrderedList(startEpochSec: Long, endEpochSec: Long): List<ReadingEntity>

    /**
     * The single earliest/latest reading actually stored within `[startEpochSec, endEpochSec]` —
     * null if the range has no data at all. Deliberately NOT the same as the range's own
     * boundaries: a user can select a wider span than the device actually has data for (e.g. an
     * overnight span picked as 23:00–00:00 that only has readings from 01:00–09:00 in the
     * middle), and both
     * [ReadingsRepository.statsForRange][com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.statsForRange]'s
     * events/hour rate and
     * [ReadingsRepository.plottedReadings][com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings]'s
     * chart need the *actual data's* span, not the selected span, to be correct — see their own
     * docs. `ORDER BY ... LIMIT 1` against an indexed column (`timestampEpochSec` is this table's
     * own primary key) is a single index seek to the first/last matching row, not a full range
     * scan, so this stays cheap regardless of how many rows fall inside the range.
     */
    @Query(
        "SELECT * FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "ORDER BY timestampEpochSec ASC LIMIT 1"
    )
    suspend fun firstInRange(startEpochSec: Long, endEpochSec: Long): ReadingEntity?

    /** See [firstInRange]'s own doc. */
    @Query(
        "SELECT * FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "ORDER BY timestampEpochSec DESC LIMIT 1"
    )
    suspend fun lastInRange(startEpochSec: Long, endEpochSec: Long): ReadingEntity?

    /**
     * The starting timestamp of every real "session" break within `[startEpochSec, endEpochSec]`
     * — every reading whose gap since the *previous* reading (by timestamp, not by anything
     * decimation kept) is at least [minGapSeconds]. Used by
     * [ReadingsRepository.plottedReadings][com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings]
     * to split the chart into separate, unconnected line segments at real gaps in the data (the
     * device plainly wasn't being worn) instead of drawing a straight line across them — see that
     * function's own doc.
     *
     * `LAG(timestampEpochSec) OVER (ORDER BY timestampEpochSec)` genuinely has to visit every row
     * in range once — there's no way to know about a gap without seeing both readings on either
     * side of it — but that comparison runs entirely inside SQLite; only the (typically tiny)
     * subset of rows that actually start a new session after a gap gets returned to the app, so
     * this stays cheap in application memory even across a multi-year range with millions of
     * rows. The window function is computed in the inner subquery, before the outer `WHERE`
     * filters it down, so this is a perfectly ordinary correlated-nothing scan as far as SQLite's
     * query planner is concerned — no special interaction with `WHERE`/window-function ordering
     * to worry about.
     */
    @Query(
        "SELECT timestampEpochSec FROM (" +
            "SELECT timestampEpochSec, " +
            "timestampEpochSec - LAG(timestampEpochSec) OVER (ORDER BY timestampEpochSec) AS gapSeconds " +
            "FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec" +
            ") WHERE gapSeconds >= :minGapSeconds ORDER BY timestampEpochSec ASC"
    )
    suspend fun sessionBoundaries(startEpochSec: Long, endEpochSec: Long, minGapSeconds: Long): List<Long>

    /**
     * One page of a range, ordered ascending, starting at [afterEpochSec] (inclusive) — keyset
     * pagination (a `WHERE >=` seek against the primary-key index), not `OFFSET`/`LIMIT`, so each
     * page is an equally cheap index seek no matter how far into a huge range it starts, unlike
     * `OFFSET`, which re-scans and discards every prior row on every call. Used by
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository] to stream through a
     * range in bounded-size chunks (e.g. for desaturation-event counting) instead of loading it
     * all into memory as one `List` — see that repository's own doc.
     */
    @Query(
        "SELECT * FROM readings " +
            "WHERE timestampEpochSec BETWEEN :afterEpochSec AND :endEpochSec " +
            "ORDER BY timestampEpochSec ASC LIMIT :limit"
    )
    suspend fun pageInRange(afterEpochSec: Long, endEpochSec: Long, limit: Int): List<ReadingEntity>

    @Query(
        "SELECT " +
            "MIN(spo2) AS minSpo2, MAX(spo2) AS maxSpo2, AVG(spo2) AS avgSpo2, " +
            "MIN(pulse) AS minPulse, MAX(pulse) AS maxPulse, AVG(pulse) AS avgPulse " +
            "FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec"
    )
    suspend fun statsForRange(startEpochSec: Long, endEpochSec: Long): ReadingStats

    /**
     * A (value, count) histogram of every SpO2 reading in range, sorted ascending by value —
     * bounded by the number of *distinct* SpO2 values (SpO2 is a percentage, so at most 101),
     * never by the row count. [ReadingsRepository][com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository]
     * computes the 95th percentile from this instead of sorting every raw reading, which is what
     * keeps that computation cheap across a multi-year range instead of needing an O(n)-memory
     * sort of every row in it.
     */
    @Query(
        "SELECT spo2 AS value, COUNT(*) AS count FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "GROUP BY spo2 ORDER BY spo2 ASC"
    )
    suspend fun spo2Histogram(startEpochSec: Long, endEpochSec: Long): List<ValueCount>

    /** Same as [spo2Histogram], for pulse — bounded by the number of distinct bpm values seen. */
    @Query(
        "SELECT pulse AS value, COUNT(*) AS count FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "GROUP BY pulse ORDER BY pulse ASC"
    )
    suspend fun pulseHistogram(startEpochSec: Long, endEpochSec: Long): List<ValueCount>

    /**
     * The four `bucketedXxx` queries below are what
     * [ReadingsRepository.plottedReadings][com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings]
     * decimates a wide range down to for charting: rows in `[startEpochSec, endEpochSec]`, in
     * timestamp order, are split into fixed-size buckets of [bucketRowSize] *rows* each (bucket
     * index = `(ROW_NUMBER() OVER (ORDER BY timestampEpochSec) - 1) / bucketRowSize`) — bucketing
     * by row count, not by a fixed time width — and each query returns, for every bucket, the
     * single row holding that bucket's minimum or maximum SpO2/pulse.
     *
     * Row-count bucketing (not equal-duration time bucketing, which an earlier version of this
     * used) is what makes this adapt to however the data is actually distributed: real usage is
     * often clustered into separate sessions with large real gaps between them (e.g. roughly
     * nightly, or even more sparsely) — sizing buckets by a fixed *time* width chosen to cover
     * the whole selected span means every one of those brief, dense sessions can fall entirely
     * inside a single time bucket together, capping the *entire session* at that one bucket's own
     * 4 extremes regardless of how much real detail it contains — a year of nightly use could
     * otherwise decimate down to only as many points as there were sessions. Row-count buckets
     * instead give each bucket a roughly equal *share of the actual data*, so dense sessions
     * naturally claim proportionally more of the point budget and sparse ones proportionally
     * less, regardless of how the real time gaps between them are shaped.
     * [ReadingsRepository.plottedReadings] separately computes each *kept* reading's on-chart
     * x-position from its real timestamp relative to the data's overall span — that (not this
     * selection step) is what keeps the x-axis itself genuinely proportional to elapsed time.
     *
     * The trick that makes "the whole row containing the min/max" a single aggregate query
     * without a self-join: SQLite specifically documents that when a query's result columns
     * contain exactly one bare `MIN()`/`MAX()` aggregate, every *other* bare (non-aggregated)
     * column in that result takes its value from the same row that produced the min/max — see
     * "Bare columns in an aggregate query" in SQLite's own `SELECT` documentation. This still
     * applies with the source being a subquery (here, the one computing `ROW_NUMBER()`) rather
     * than a plain table — confirmed directly against SQLite 3.50. `MIN(spo2)`/`MAX(spo2)`/
     * `MIN(pulse)`/`MAX(pulse)` below exist in the query purely to trigger that behavior for the
     * bare `timestampEpochSec`/`spo2`/`pulse` columns actually wanted;
     * [RewriteQueriesToDropUnusedColumns] has Room wrap the query so that extra aggregate column
     * never has to be reflected in [ReadingEntity] itself.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT timestampEpochSec, spo2, pulse, MIN(spo2) FROM (" +
            "SELECT timestampEpochSec, spo2, pulse, " +
            "(ROW_NUMBER() OVER (ORDER BY timestampEpochSec) - 1) / :bucketRowSize AS bucket " +
            "FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec" +
            ") GROUP BY bucket"
    )
    suspend fun bucketedMinSpo2(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long): List<ReadingEntity>

    /** See [bucketedMinSpo2]'s own doc. */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT timestampEpochSec, spo2, pulse, MAX(spo2) FROM (" +
            "SELECT timestampEpochSec, spo2, pulse, " +
            "(ROW_NUMBER() OVER (ORDER BY timestampEpochSec) - 1) / :bucketRowSize AS bucket " +
            "FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec" +
            ") GROUP BY bucket"
    )
    suspend fun bucketedMaxSpo2(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long): List<ReadingEntity>

    /** See [bucketedMinSpo2]'s own doc. */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT timestampEpochSec, spo2, pulse, MIN(pulse) FROM (" +
            "SELECT timestampEpochSec, spo2, pulse, " +
            "(ROW_NUMBER() OVER (ORDER BY timestampEpochSec) - 1) / :bucketRowSize AS bucket " +
            "FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec" +
            ") GROUP BY bucket"
    )
    suspend fun bucketedMinPulse(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long): List<ReadingEntity>

    /** See [bucketedMinSpo2]'s own doc. */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT timestampEpochSec, spo2, pulse, MAX(pulse) FROM (" +
            "SELECT timestampEpochSec, spo2, pulse, " +
            "(ROW_NUMBER() OVER (ORDER BY timestampEpochSec) - 1) / :bucketRowSize AS bucket " +
            "FROM readings WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec" +
            ") GROUP BY bucket"
    )
    suspend fun bucketedMaxPulse(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    /**
     * Every already-stored timestamp within `[startEpochSec, endEpochSec]` — used by
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.importCsv] to drop
     * incoming rows that already exist rather than relying on [insertAll]'s REPLACE conflict
     * strategy to silently overwrite them, so a re-sync's "N rows synced" count reflects rows
     * actually new to this database, not every row the ESP32 happened to resend. A single range
     * scan against this table's own primary-key index, rather than one query per incoming
     * timestamp — immune to SQLite's ~999-bound-parameter ceiling that a plain
     * `WHERE timestampEpochSec IN (:allIncomingTimestamps)` would hit once a resync's CSV spans
     * more rows than that (see PROTOCOL.md: every `REQUEST_DATA` now resends the device's entire
     * history, not just what's new).
     */
    @Query(
        "SELECT timestampEpochSec FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec"
    )
    suspend fun existingTimestampsInRange(startEpochSec: Long, endEpochSec: Long): List<Long>
}
