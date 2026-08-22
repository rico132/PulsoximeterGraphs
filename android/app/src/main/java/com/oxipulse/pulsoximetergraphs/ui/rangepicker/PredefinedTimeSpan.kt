package com.oxipulse.pulsoximetergraphs.ui.rangepicker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Quick-select time spans offered as shortcuts alongside the full [DateTimeRangePickerDialog] —
 * see GraphScreen's toolbar menu. Every span starts at local midnight (00:00) exactly [amount]
 * [unit] before today's date, and ends at the actual current instant, unrounded — e.g. "Last day"
 * is "yesterday at 00:00 through right now", not a rolling last-24-hours window (that's
 * GraphViewModel's separate `defaultRange()`, used only for this screen's very first, un-picked
 * state) and not "today so far" either.
 */
enum class PredefinedTimeSpan(val label: String, private val amount: Long, private val unit: ChronoUnit) {
    LAST_DAY("Last day", 1, ChronoUnit.DAYS),
    LAST_2_DAYS("Last 2 days", 2, ChronoUnit.DAYS),
    LAST_3_DAYS("Last 3 days", 3, ChronoUnit.DAYS),
    LAST_WEEK("Last week", 1, ChronoUnit.WEEKS),
    LAST_2_WEEKS("Last 2 weeks", 2, ChronoUnit.WEEKS),
    LAST_MONTH("Last month", 1, ChronoUnit.MONTHS),
    LAST_2_MONTHS("Last 2 months", 2, ChronoUnit.MONTHS),
    LAST_6_MONTHS("Last 6 months", 6, ChronoUnit.MONTHS),
    LAST_YEAR("Last year", 1, ChronoUnit.YEARS),
    ;

    /**
     * [zone] and [now] are only overridden by tests — real callers always want the device's
     * actual current zone/instant, which is why both default to it rather than being required.
     * [unit] is a calendar unit (WEEKS/MONTHS/YEARS), so this correctly rides over
     * month-length/leap-year differences via [LocalDate.minus] rather than approximating with a
     * fixed day count (e.g. "last month" from March 31st lands on the *actual* last day of
     * February, not 30 days back).
     */
    fun toRange(zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): ClosedRange<Instant> {
        // LocalDate.ofInstant(now, zone), NOT LocalDate.now(zone) -- the latter reads the real
        // system clock directly, ignoring the [now] passed in above (only the range's end would
        // then respect an injected [now], not "today" for the start-date subtraction below).
        val today = LocalDate.ofInstant(now, zone)
        val startDate: LocalDate = today.minus(amount, unit)
        val start = startDate.atStartOfDay(zone).toInstant()
        return start..now
    }
}
