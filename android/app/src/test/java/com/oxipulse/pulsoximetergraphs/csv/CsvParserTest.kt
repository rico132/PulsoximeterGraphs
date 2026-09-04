package com.oxipulse.pulsoximetergraphs.csv

import com.oxipulse.pulsoximetergraphs.data.csv.CsvParser
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun `parses the sample line from PROTOCOL md`() {
        val text = "DATE,TIME,SPO2,PULSE\n2026-08-12, 19:15:38, 96, 75\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(1, result.readings.size)
        assertEquals(0, result.skippedRowCount)
        val reading = result.readings.first()
        assertEquals(96, reading.spo2)
        assertEquals(75, reading.pulse)
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val text = "DATE,TIME,SPO2,PULSE\r\n2026-08-12, 19:15:38, 96, 75\r\n2026-08-12, 19:15:39, 95, 76\r\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(2, result.readings.size)
        assertEquals(0, result.skippedRowCount)
    }

    @Test
    fun `tolerates bare LF line endings`() {
        val text = "DATE,TIME,SPO2,PULSE\n2026-08-12, 19:15:38, 96, 75\n2026-08-12, 19:15:39, 95, 76\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(2, result.readings.size)
    }

    @Test
    fun `tolerates plain commas without a following space`() {
        val text = "DATE,TIME,SPO2,PULSE\n2026-08-12,19:15:38,96,75\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(1, result.readings.size)
        assertEquals(96, result.readings.first().spo2)
    }

    @Test
    fun `skips malformed rows without aborting the whole parse`() {
        val text = buildString {
            append("DATE,TIME,SPO2,PULSE\n")
            append("2026-08-12, 19:15:38, 96, 75\n") // valid
            append("not-a-date, 19:15:39, 95, 76\n") // malformed date
            append("2026-08-12, 19:15:40, notanumber, 76\n") // malformed spo2
            append("2026-08-12, 19:15:41\n") // wrong field count
            append("2026-08-12, 19:15:42, 94, 77\n") // valid
        }
        val result = CsvParser.parse(text, utc)

        assertEquals(2, result.readings.size)
        assertEquals(3, result.skippedRowCount)
        assertEquals(5, result.totalDataRowCount)
    }

    @Test
    fun `missing header is fine — rows are still parsed`() {
        val text = "2026-08-12, 19:15:38, 96, 75\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(1, result.readings.size)
    }

    @Test
    fun `empty input yields an empty, non-fatal result`() {
        val result = CsvParser.parse("", utc)

        assertTrue(result.readings.isEmpty())
        assertEquals(0, result.skippedRowCount)
        assertEquals(0, result.totalDataRowCount)
    }

    @Test
    fun `header-only input yields an empty result`() {
        val result = CsvParser.parse("DATE,TIME,SPO2,PULSE\n", utc)

        assertTrue(result.readings.isEmpty())
        assertEquals(0, result.totalDataRowCount)
    }

    @Test
    fun `re-parsing the same row twice is a caller-level dedupe concern, not the parser's`() {
        // The parser itself has no dedupe logic (that's Room's OnConflictStrategy.REPLACE,
        // keyed on timestamp) — verify it simply parses both occurrences here.
        val text = "2026-08-12, 19:15:38, 96, 75\n2026-08-12, 19:15:38, 96, 75\n"
        val result = CsvParser.parse(text, utc)

        assertEquals(2, result.readings.size)
        assertEquals(result.readings[0].timestampEpochSec, result.readings[1].timestampEpochSec)
    }

    @Test
    fun `epoch seconds are computed using the supplied zone`() {
        val text = "2026-08-12, 19:15:38, 96, 75\n"
        val utcResult = CsvParser.parse(text, ZoneOffset.UTC)
        val plusOneResult = CsvParser.parse(text, ZoneOffset.ofHours(1))

        // The same wall-clock time in a zone one hour ahead of UTC corresponds to an earlier
        // instant (UTC-3600s), since UTC+1 reaches that clock reading before UTC does.
        assertEquals(
            3600L,
            utcResult.readings.first().timestampEpochSec - plusOneResult.readings.first().timestampEpochSec,
        )
    }

    @Test
    fun `format renders a row that parseRow accepts back unchanged`() {
        val epochSec = LocalDateTime.of(2026, 8, 12, 19, 15, 38).toEpochSecond(ZoneOffset.UTC)
        val reading = ReadingEntity(timestampEpochSec = epochSec, spo2 = 96, pulse = 75)

        val row = CsvParser.format(reading, utc)
        assertEquals("2026-08-12, 19:15:38, 96, 75", row)

        val roundTripped = CsvParser.parse("${CsvParser.HEADER_LINE}\r\n$row\r\n", utc)
        assertEquals(1, roundTripped.readings.size)
        assertEquals(reading, roundTripped.readings.first())
    }

    @Test
    fun `format uses the supplied zone, mirroring parse`() {
        val epochSec = LocalDateTime.of(2026, 8, 12, 19, 15, 38).toEpochSecond(ZoneOffset.UTC)
        val reading = ReadingEntity(timestampEpochSec = epochSec, spo2 = 96, pulse = 75)

        val utcRow = CsvParser.format(reading, ZoneOffset.UTC)
        val plusOneRow = CsvParser.format(reading, ZoneOffset.ofHours(1))

        assertEquals("2026-08-12, 19:15:38, 96, 75", utcRow)
        assertEquals("2026-08-12, 20:15:38, 96, 75", plusOneRow)
    }
}
