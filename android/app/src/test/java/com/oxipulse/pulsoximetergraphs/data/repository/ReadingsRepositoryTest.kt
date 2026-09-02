package com.oxipulse.pulsoximetergraphs.data.repository

import com.oxipulse.pulsoximetergraphs.data.db.PlottedReading
import com.oxipulse.pulsoximetergraphs.data.db.ReadingDao
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import com.oxipulse.pulsoximetergraphs.data.db.ValueCount
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory fake rather than a mock (no mocking library is a project dependency — see
 * ThresholdConfigTest/CsvParserTest's own plain-JUnit style) — just enough of [ReadingDao] for
 * [ReadingsRepository]'s own logic to exercise against, not a real SQL engine. The bucketed-
 * extreme and histogram queries are re-implemented here in plain Kotlin, matching what their SQL
 * counterparts compute (see [ReadingDao]'s own docs) rather than trying to run real SQLite.
 */
private class FakeReadingDao(seed: List<ReadingEntity> = emptyList()) : ReadingDao {
    val rows = seed.associateBy { it.timestampEpochSec }.toMutableMap()

    private fun inRange(startEpochSec: Long, endEpochSec: Long): List<ReadingEntity> =
        rows.values.filter { it.timestampEpochSec in startEpochSec..endEpochSec }.sortedBy { it.timestampEpochSec }

    override suspend fun insertAll(readings: List<ReadingEntity>) {
        readings.forEach { rows[it.timestampEpochSec] = it }
    }

    override fun observeCountInRange(startEpochSec: Long, endEpochSec: Long): Flow<Int> =
        flowOf(inRange(startEpochSec, endEpochSec).size)

    override suspend fun rangeOrderedList(startEpochSec: Long, endEpochSec: Long): List<ReadingEntity> =
        inRange(startEpochSec, endEpochSec)

    override suspend fun firstInRange(startEpochSec: Long, endEpochSec: Long): ReadingEntity? =
        inRange(startEpochSec, endEpochSec).firstOrNull()

    override suspend fun lastInRange(startEpochSec: Long, endEpochSec: Long): ReadingEntity? =
        inRange(startEpochSec, endEpochSec).lastOrNull()

    override suspend fun sessionBoundaries(startEpochSec: Long, endEpochSec: Long, minGapSeconds: Long): List<Long> {
        val sorted = inRange(startEpochSec, endEpochSec)
        return (1 until sorted.size)
            .filter { i -> sorted[i].timestampEpochSec - sorted[i - 1].timestampEpochSec >= minGapSeconds }
            .map { i -> sorted[i].timestampEpochSec }
    }

    override suspend fun pageInRange(afterEpochSec: Long, endEpochSec: Long, limit: Int): List<ReadingEntity> =
        inRange(afterEpochSec, endEpochSec).take(limit)

    override suspend fun statsForRange(startEpochSec: Long, endEpochSec: Long): ReadingStats {
        val readings = inRange(startEpochSec, endEpochSec)
        if (readings.isEmpty()) {
            return ReadingStats(minSpo2 = null, maxSpo2 = null, avgSpo2 = null, minPulse = null, maxPulse = null, avgPulse = null)
        }
        return ReadingStats(
            minSpo2 = readings.minOf { it.spo2 },
            maxSpo2 = readings.maxOf { it.spo2 },
            avgSpo2 = readings.map { it.spo2 }.average(),
            minPulse = readings.minOf { it.pulse },
            maxPulse = readings.maxOf { it.pulse },
            avgPulse = readings.map { it.pulse }.average(),
        )
    }

    override suspend fun spo2Histogram(startEpochSec: Long, endEpochSec: Long): List<ValueCount> =
        histogramOf(inRange(startEpochSec, endEpochSec).map { it.spo2 })

    override suspend fun pulseHistogram(startEpochSec: Long, endEpochSec: Long): List<ValueCount> =
        histogramOf(inRange(startEpochSec, endEpochSec).map { it.pulse })

    private fun histogramOf(values: List<Int>): List<ValueCount> =
        values.groupingBy { it }.eachCount().map { (value, count) -> ValueCount(value, count) }.sortedBy { it.value }

    /** Mirrors the SQLite "bare column takes the min/max row's value" trick — see
     * [ReadingDao.bucketedMinSpo2]'s own doc — by keeping the *first* (ascending-timestamp)
     * reading achieving the extreme within each bucket, same as a single-aggregate SQLite query
     * scanning in primary-key (ROW_NUMBER ordering) order would. Buckets are consecutive chunks
     * of [bucketRowSize] rows by rank -- the same grouping `(ROW_NUMBER() - 1) / bucketRowSize`
     * produces -- not a time-width split; see that query's own doc for why row count, not time. */
    private fun bucketedExtreme(
        startEpochSec: Long,
        endEpochSec: Long,
        bucketRowSize: Long,
        moreExtreme: (current: ReadingEntity, candidate: ReadingEntity) -> Boolean,
    ): List<ReadingEntity> =
        inRange(startEpochSec, endEpochSec)
            .chunked(bucketRowSize.toInt())
            .map { bucket -> bucket.reduce { current, candidate -> if (moreExtreme(current, candidate)) candidate else current } }

    override suspend fun bucketedMinSpo2(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketRowSize) { a, b -> b.spo2 < a.spo2 }

    override suspend fun bucketedMaxSpo2(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketRowSize) { a, b -> b.spo2 > a.spo2 }

    override suspend fun bucketedMinPulse(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketRowSize) { a, b -> b.pulse < a.pulse }

    override suspend fun bucketedMaxPulse(startEpochSec: Long, endEpochSec: Long, bucketRowSize: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketRowSize) { a, b -> b.pulse > a.pulse }

    override suspend fun count(): Int = rows.size

    override suspend fun existingTimestampsInRange(startEpochSec: Long, endEpochSec: Long): List<Long> =
        rows.keys.filter { it in startEpochSec..endEpochSec }
}

private const val HEADER = "DATE,TIME,SPO2,PULSE"

private fun row(date: String, time: String, spo2: Int, pulse: Int) = "$date, $time, $spo2, $pulse"

/** Same zone CsvParser.parse defaults to, so a seeded row's epoch matches parsing the same date/time. */
private fun epochOf(date: String, time: String): Long =
    LocalDateTime.parse("${date}T$time").atZone(ZoneId.systemDefault()).toEpochSecond()

class ReadingsRepositoryTest {

    @Test
    fun `first import inserts every row`() = runTest {
        val dao = FakeReadingDao()
        val repo = ReadingsRepository(dao)

        val csv = listOf(
            HEADER,
            row("2026-08-12", "19:15:38", 96, 75),
            row("2026-08-12", "19:15:39", 97, 76),
        ).joinToString("\r\n")

        val result = repo.importCsv(csv)

        assertEquals(2, result.readings.size)
        assertEquals(2, dao.count())
    }

    @Test
    fun `re-syncing the same row drops it instead of re-inserting`() = runTest {
        val epoch = epochOf("2026-08-12", "19:15:38")
        val dao = FakeReadingDao(seed = listOf(ReadingEntity(timestampEpochSec = epoch, spo2 = 96, pulse = 75)))
        val repo = ReadingsRepository(dao)

        // Same date/time as the seeded row -- simulates the ESP32 resending its entire history on
        // every REQUEST_DATA (see PROTOCOL.md), including a row this database already has from a
        // previous sync.
        val csv = listOf(HEADER, row("2026-08-12", "19:15:38", 96, 75)).joinToString("\r\n")

        val result = repo.importCsv(csv)

        assertEquals("the already-known row must be dropped, not re-counted as inserted", 0, result.readings.size)
        assertEquals("the database must still only have the one original row", 1, dao.count())
    }

    @Test
    fun `a resync with one new row and one already-known row inserts only the new one`() = runTest {
        val knownEpoch = epochOf("2026-08-12", "19:15:38")
        val dao = FakeReadingDao(
            seed = listOf(ReadingEntity(timestampEpochSec = knownEpoch, spo2 = 96, pulse = 75)),
        )
        val repo = ReadingsRepository(dao)

        val csv = listOf(
            HEADER,
            row("2026-08-12", "19:15:38", 96, 75), // already known
            row("2026-08-12", "19:15:39", 98, 80), // new
        ).joinToString("\r\n")

        val result = repo.importCsv(csv)

        assertEquals(1, result.readings.size)
        assertEquals(98, result.readings.single().spo2)
        assertEquals(2, dao.count())
    }

    @Test
    fun `parse-skipped and total row counts are untouched by dedup`() = runTest {
        val dao = FakeReadingDao()
        val repo = ReadingsRepository(dao)

        val csv = listOf(
            HEADER,
            "not, a, valid, row, at, all",
            row("2026-08-12", "19:15:38", 96, 75),
        ).joinToString("\r\n")

        val result = repo.importCsv(csv)

        assertEquals(1, result.skippedRowCount)
        assertEquals(2, result.totalDataRowCount)
        assertEquals(1, result.readings.size)
    }

    // --- plottedReadings / statsForRange: the bounded-memory read path added so a multi-year
    // range never has to materialize every raw row just to render a chart or compute stats. ---

    private fun readingsOverSeconds(count: Int, spo2: (Int) -> Int = { 95 }, pulse: (Int) -> Int = { 70 }) =
        (0 until count).map { i -> ReadingEntity(timestampEpochSec = i.toLong(), spo2 = spo2(i), pulse = pulse(i)) }

    @Test
    fun `plottedReadings returns every row when the range is at or under the cap`() = runTest {
        val readings = readingsOverSeconds(ReadingsRepository.MAX_PLOTTED_POINTS)
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertEquals(readings, plotted.map { it.reading })
    }

    @Test
    fun `plottedReadings decimates a huge range but keeps a lone spike visible`() = runTest {
        val total = ReadingsRepository.MAX_PLOTTED_POINTS * 100
        val spikeAt = total / 2
        // A flat baseline with one single-second desaturation spike buried in the middle -- a
        // naive "keep every Nth point" decimation would very likely skip straight over this.
        val readings = readingsOverSeconds(total, spo2 = { i -> if (i == spikeAt) 70 else 98 })
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertTrue("must not just return everything for a range this large", plotted.size < total)
        assertTrue(
            "the single spike reading must survive decimation",
            plotted.any { it.reading.timestampEpochSec == spikeAt.toLong() && it.reading.spo2 == 70 },
        )
    }

    @Test
    fun `plottedReadings uses close to the full point budget when a wide selection is mostly empty`() = runTest {
        // A full day selected, but the device was only actually worn for a few hours in the
        // middle of it. Row-count bucketing (see ReadingDao.bucketedMinSpo2's own doc) only ever
        // sees the rows that exist -- the empty 16 hours contribute no rows at all, so they can't
        // "waste" any of the bucket budget the way a fixed-time-width bucketing scheme would.
        val selectedStart = 0L
        val selectedEnd = 24 * 3600L // a full day
        val dataStart = 3600L // data only from 1:00...
        val dataEnd = dataStart + 8 * 3600L // ...through 9:00 -- a third of the selected day
        // Varying, not flat, spo2/pulse: a flat signal ties on every bucket's own min/max, so
        // (with the fake DAO's first-occurrence tie-break -- see bucketedExtreme's own doc) every
        // bucket would only ever contribute its single first row, which wouldn't actually
        // exercise "how many buckets have data" at all.
        val readings = (dataStart..dataEnd).map { ts ->
            ReadingEntity(timestampEpochSec = ts, spo2 = 90 + (ts % 10).toInt(), pulse = 60 + (ts % 15).toInt())
        }
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(selectedStart)..Instant.ofEpochSecond(selectedEnd)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertTrue(
            "expected close to the full point budget given dense, contiguous real data, got ${plotted.size}",
            plotted.size > 400,
        )
    }

    @Test
    fun `plottedReadings doesn't collapse each of several sparse sessions into one point`() = runTest {
        // Exactly the reported regression: selecting "last year" showed only ~12 points, one per
        // roughly-monthly usage session. A fixed *time*-width bucketing scheme, sized to span the
        // whole year, let each entire brief session collapse into that one time bucket's own 4
        // extremes. Row-count bucketing instead gives each session's own rows a share of the
        // point budget proportional to how many rows it actually has, regardless of how far apart
        // in time the sessions are.
        val sessionCount = 12
        val sessionRowCount = 100
        val sessionSpacingSeconds = 30L * 24 * 3600 // roughly monthly
        val sessionDurationSeconds = 3600L // each session is a brief, dense hour
        val readings = (0 until sessionCount).flatMap { session ->
            val sessionStart = session * sessionSpacingSeconds
            (0 until sessionRowCount).map { i ->
                val ts = sessionStart + i * (sessionDurationSeconds / sessionRowCount)
                ReadingEntity(timestampEpochSec = ts, spo2 = 90 + (i % 10), pulse = 60 + (i % 15))
            }
        }
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertTrue(
            "expected meaningfully more than ~1 point per session (the bug's ceiling), got ${plotted.size}",
            plotted.size > sessionCount * 5,
        )
    }

    @Test
    fun `plottedReadings' xIndex reflects real time gaps, not list position`() = runTest {
        // Two dense, internally-varied clusters (each spanning enough buckets on its own to have
        // a well-defined "typical step") with a real gap between them ~10x either cluster's own
        // span -- adjacent points straddling that gap must end up with xIndex values
        // proportionally far apart, not merely one bucket apart the way adjacent *list positions*
        // always are. Row count is kept comfortably above MAX_PLOTTED_POINTS so this exercises the
        // bucketed path, not the small-range full-fidelity path (which -- see PlottedReading's own
        // doc -- can legitimately let an entire tight burst share one xIndex; that's a separate,
        // documented tradeoff this test isn't about).
        val clusterSpanSeconds = 10_000L
        val gapSeconds = clusterSpanSeconds * 10
        fun cluster(startTs: Long) = (startTs..(startTs + clusterSpanSeconds) step 10).map { ts ->
            ReadingEntity(timestampEpochSec = ts, spo2 = 90 + (ts % 10).toInt(), pulse = 60 + (ts % 15).toInt())
        }
        val clusterA = cluster(0L)
        val gapStart = clusterSpanSeconds
        val gapEnd = clusterSpanSeconds + gapSeconds
        val clusterB = cluster(gapEnd)
        val readings = clusterA + clusterB
        assertTrue("need >MAX_PLOTTED_POINTS rows to exercise the bucketed path", readings.size > ReadingsRepository.MAX_PLOTTED_POINTS)
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val plotted = repo.plottedReadings(range, totalCount = readings.size).sortedBy { it.xIndex }

        val lastOfA = plotted.last { it.reading.timestampEpochSec < gapStart }
        val firstOfB = plotted.first { it.reading.timestampEpochSec >= gapEnd }
        val xIndexGapAcrossClusters = firstOfB.xIndex - lastOfA.xIndex
        val typicalWithinClusterXIndexGap = plotted
            .zipWithNext { a, b -> b.xIndex - a.xIndex }
            .filter { it > 0 }
            .min()
        assertTrue(
            "the real ~${gapSeconds}s gap between clusters must produce a far larger xIndex jump " +
                "($xIndexGapAcrossClusters) than a typical within-cluster step ($typicalWithinClusterXIndexGap)",
            xIndexGapAcrossClusters > typicalWithinClusterXIndexGap * 5,
        )
    }

    @Test
    fun `plottedReadings assigns a different sessionIndex across a real gap, same index within a session`() = runTest {
        val sessionGap = ReadingsRepository.SESSION_GAP_SECONDS
        val sessionA = (0L until 10).map { ts -> ReadingEntity(timestampEpochSec = ts, spo2 = 95, pulse = 70) }
        val sessionBStart = sessionGap + 100
        val sessionB = (sessionBStart until sessionBStart + 10).map { ts -> ReadingEntity(timestampEpochSec = ts, spo2 = 95, pulse = 70) }
        val readings = sessionA + sessionB
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        val sessionIndicesOfA = plotted.filter { it.reading.timestampEpochSec < sessionBStart }.map { it.sessionIndex }.toSet()
        val sessionIndicesOfB = plotted.filter { it.reading.timestampEpochSec >= sessionBStart }.map { it.sessionIndex }.toSet()
        assertEquals("every reading within one session must share the same sessionIndex", 1, sessionIndicesOfA.size)
        assertEquals("every reading within the other session must share the same sessionIndex", 1, sessionIndicesOfB.size)
        assertTrue("the two sessions must have different sessionIndex values", sessionIndicesOfA != sessionIndicesOfB)
    }

    @Test
    fun `plottedReadings keeps one sessionIndex when the gap is under the session threshold`() = runTest {
        // Just short of SESSION_GAP_SECONDS -- a brief BLE/USB hiccup, not a real new session.
        val gap = ReadingsRepository.SESSION_GAP_SECONDS - 1
        val readings = listOf(
            ReadingEntity(timestampEpochSec = 0, spo2 = 95, pulse = 70),
            ReadingEntity(timestampEpochSec = gap, spo2 = 96, pulse = 71),
        )
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(gap)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertEquals(setOf(0), plotted.map { it.sessionIndex }.toSet())
    }

    @Test
    fun `statsForRange computes the 95th percentile from the histogram, not a full sort`() = runTest {
        // Nearest-rank nature: 20 values 1..20, ceil(20 * 0.95) = 19th smallest = 19.
        val readings = (1..20).map { v -> ReadingEntity(timestampEpochSec = v.toLong(), spo2 = v + 75, pulse = 70) }
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(1)..Instant.ofEpochSecond(20)

        val stats = repo.statsForRange(range, spo2EventThreshold = 90)

        assertEquals(94, stats.p95Spo2) // the 19th of 20 ascending values 76..95 is 94.
    }

    @Test
    fun `statsForRange's event count matches a desaturation event straddling a page boundary`() = runTest {
        // The page size is what countSpo2EventsInRange chunks the range into internally -- a
        // desaturation run that starts just before a page boundary and ends just after it must
        // still be recognized as a single continuous event, not lost or double-counted at the
        // seam between two pages.
        val pageSize = ReadingsRepository.EVENT_COUNT_PAGE_SIZE
        val readings = readingsOverSeconds(pageSize + 10, spo2 = { i ->
            if (i in (pageSize - 2)..(pageSize + 2)) 85 else 98
        })
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(0)..Instant.ofEpochSecond(readings.last().timestampEpochSec)

        val stats = repo.statsForRange(range, spo2EventThreshold = 90)

        assertEquals(1, stats.spo2EventCount)
    }

    @Test
    fun `statsForRange divides by the actual data span, not the wider selected range`() = runTest {
        // Exactly the reported scenario: a 25-hour selected range (23:00 the day before through
        // 00:00 the day after), but the device only actually has 8 hours of data in the middle
        // (01:00-09:00) with 2 desaturation events in it -- the rate must be 2/8 = 0.25/hr, not
        // 2/25.
        val selectedStart = epochOf("2026-08-11", "23:00:00")
        val selectedEnd = epochOf("2026-08-13", "00:00:00")
        val dataStart = epochOf("2026-08-12", "01:00:00")
        val dataEnd = epochOf("2026-08-12", "09:00:00")
        assertEquals("data must span exactly 8 hours for this test to mean what it says", 8 * 3600L, dataEnd - dataStart)

        val readings = listOf(
            ReadingEntity(timestampEpochSec = dataStart, spo2 = 85, pulse = 70), // event 1
            ReadingEntity(timestampEpochSec = dataStart + 3600, spo2 = 98, pulse = 70), // recovers, long gap after
            ReadingEntity(timestampEpochSec = dataStart + 7200, spo2 = 85, pulse = 70), // event 2
            ReadingEntity(timestampEpochSec = dataEnd, spo2 = 98, pulse = 70),
        )
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(selectedStart)..Instant.ofEpochSecond(selectedEnd)

        val stats = repo.statsForRange(range, spo2EventThreshold = 90)

        assertEquals(2, stats.spo2EventCount)
        assertEquals(0.25, stats.spo2EventsPerHour!!, 1e-9)
    }

    @Test
    fun `plottedReadings always includes the exact first and last reading in range`() = runTest {
        // A wide, sparse range (bucket width ~4800s, well over the 500s offsets below) whose
        // bucket-local SpO2 extremes don't happen to fall on the range's own first/last reading --
        // the decimated chart must still start and end exactly at the true boundary readings, not
        // wherever the nearest bucket's extreme happened to be.
        val start = 0L
        val end = 600_000L
        val boundaryAndSpikes = listOf(
            ReadingEntity(timestampEpochSec = start, spo2 = 95, pulse = 70), // unremarkable boundary reading
            ReadingEntity(timestampEpochSec = start + 500, spo2 = 80, pulse = 70), // this bucket's real extreme
            ReadingEntity(timestampEpochSec = end - 500, spo2 = 100, pulse = 70), // this bucket's real extreme
            ReadingEntity(timestampEpochSec = end, spo2 = 95, pulse = 70), // unremarkable boundary reading
        )
        // Sparse fill (>MAX_PLOTTED_POINTS rows, spaced 1000s apart) just to trigger the decimated
        // path -- not meant to be realistic sampling density.
        val sparseFill = (0..600).map { i -> ReadingEntity(timestampEpochSec = (i * 1000L).coerceAtMost(end), spo2 = 95, pulse = 70) }
        val readings = (boundaryAndSpikes + sparseFill).distinctBy { it.timestampEpochSec }
        val dao = FakeReadingDao(readings)
        val repo = ReadingsRepository(dao)
        val range = Instant.ofEpochSecond(start)..Instant.ofEpochSecond(end)

        val plotted = repo.plottedReadings(range, totalCount = readings.size)

        assertEquals(
            "the very first plotted reading must be the range's true first reading",
            start,
            plotted.first().reading.timestampEpochSec,
        )
        assertEquals(
            "the very last plotted reading must be the range's true last reading",
            end,
            plotted.last().reading.timestampEpochSec,
        )
    }
}
