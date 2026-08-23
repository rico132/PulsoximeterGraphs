package com.oxipulse.pulsoximetergraphs.data.repository

import com.oxipulse.pulsoximetergraphs.data.db.ReadingDao
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-memory fake rather than a mock (no mocking library is a project dependency — see
 * ThresholdConfigTest/CsvParserTest's own plain-JUnit style) — just enough of [ReadingDao] for
 * [ReadingsRepository]'s own logic to exercise against, not a real SQL engine.
 */
private class FakeReadingDao(seed: List<ReadingEntity> = emptyList()) : ReadingDao {
    val rows = seed.associateBy { it.timestampEpochSec }.toMutableMap()

    override suspend fun insertAll(readings: List<ReadingEntity>) {
        readings.forEach { rows[it.timestampEpochSec] = it }
    }

    override fun observeRange(startEpochSec: Long, endEpochSec: Long): Flow<List<ReadingEntity>> =
        flowOf(rows.values.filter { it.timestampEpochSec in startEpochSec..endEpochSec })

    override suspend fun statsForRange(startEpochSec: Long, endEpochSec: Long): ReadingStats =
        ReadingStats(null, null, null, minPulse = null, maxPulse = null, avgPulse = null)

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
}
