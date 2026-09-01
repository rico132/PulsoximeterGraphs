package com.oxipulse.pulsoximetergraphs.data.repository

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

    private fun bucketIndex(ts: Long, startEpochSec: Long, bucketCount: Int, spanSeconds: Long): Long =
        ((ts - startEpochSec) * bucketCount) / spanSeconds

    /** Mirrors the SQLite "bare column takes the min/max row's value" trick — see
     * [ReadingDao.bucketedMinSpo2]'s own doc — by keeping the *first* (ascending-timestamp)
     * reading achieving the extreme within each bucket, same as a single-aggregate SQLite query
     * scanning in primary-key order would. */
    private fun bucketedExtreme(
        startEpochSec: Long,
        endEpochSec: Long,
        bucketCount: Int,
        spanSeconds: Long,
        moreExtreme: (current: ReadingEntity, candidate: ReadingEntity) -> Boolean,
    ): List<ReadingEntity> =
        inRange(startEpochSec, endEpochSec)
            .groupBy { bucketIndex(it.timestampEpochSec, startEpochSec, bucketCount, spanSeconds) }
            .values
            .map { bucket -> bucket.reduce { current, candidate -> if (moreExtreme(current, candidate)) candidate else current } }

    override suspend fun bucketedMinSpo2(startEpochSec: Long, endEpochSec: Long, bucketCount: Int, spanSeconds: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketCount, spanSeconds) { a, b -> b.spo2 < a.spo2 }

    override suspend fun bucketedMaxSpo2(startEpochSec: Long, endEpochSec: Long, bucketCount: Int, spanSeconds: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketCount, spanSeconds) { a, b -> b.spo2 > a.spo2 }

    override suspend fun bucketedMinPulse(startEpochSec: Long, endEpochSec: Long, bucketCount: Int, spanSeconds: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketCount, spanSeconds) { a, b -> b.pulse < a.pulse }

    override suspend fun bucketedMaxPulse(startEpochSec: Long, endEpochSec: Long, bucketCount: Int, spanSeconds: Long) =
        bucketedExtreme(startEpochSec, endEpochSec, bucketCount, spanSeconds) { a, b -> b.pulse > a.pulse }

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

        assertEquals(readings, plotted)
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
            plotted.any { it.timestampEpochSec == spikeAt.toLong() && it.spo2 == 70 },
        )
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
}
