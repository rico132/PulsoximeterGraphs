package com.oxipulse.pulsoximetergraphs.ui.rangepicker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PredefinedTimeSpanTest {

    // A fixed, arbitrary "now" — 2026-03-15 14:30:00 UTC — used as both the zone's local
    // instant and the returned range's end, so every assertion below can be computed by hand.
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = LocalDate.of(2026, 3, 15).atTime(14, 30).toInstant(ZoneOffset.UTC)

    private fun midnightOf(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    @Test
    fun `last day starts at midnight one day back`() {
        val range = PredefinedTimeSpan.LAST_DAY.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2026, 3, 14)), range.start)
        assertEquals(now, range.endInclusive)
    }

    @Test
    fun `last 3 days starts at midnight three days back`() {
        val range = PredefinedTimeSpan.LAST_3_DAYS.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2026, 3, 12)), range.start)
        assertEquals(now, range.endInclusive)
    }

    @Test
    fun `last week starts at midnight seven days back`() {
        val range = PredefinedTimeSpan.LAST_WEEK.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2026, 3, 8)), range.start)
    }

    @Test
    fun `last 2 weeks starts at midnight fourteen days back`() {
        val range = PredefinedTimeSpan.LAST_2_WEEKS.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2026, 3, 1)), range.start)
    }

    @Test
    fun `last month uses calendar month subtraction`() {
        val range = PredefinedTimeSpan.LAST_MONTH.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2026, 2, 15)), range.start)
    }

    @Test
    fun `last month lands on the real last day of a shorter month, not 30 days back`() {
        // From March 31st, "one calendar month back" is February 28th (2027 isn't a leap year) —
        // a fixed 30-day subtraction would instead land on March 1st, in the same month.
        val endOfMarch = LocalDate.of(2027, 3, 31).atTime(9, 0).toInstant(ZoneOffset.UTC)
        val range = PredefinedTimeSpan.LAST_MONTH.toRange(zone, endOfMarch)
        assertEquals(midnightOf(LocalDate.of(2027, 2, 28)), range.start)
    }

    @Test
    fun `last 6 months starts six calendar months back`() {
        val range = PredefinedTimeSpan.LAST_6_MONTHS.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2025, 9, 15)), range.start)
    }

    @Test
    fun `last year starts one calendar year back`() {
        val range = PredefinedTimeSpan.LAST_YEAR.toRange(zone, now)
        assertEquals(midnightOf(LocalDate.of(2025, 3, 15)), range.start)
    }

    @Test
    fun `every span ends exactly at now, unrounded`() {
        for (span in PredefinedTimeSpan.entries) {
            assertEquals(span.name, now, span.toRange(zone, now).endInclusive)
        }
    }
}
