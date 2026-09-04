package com.oxipulse.pulsoximetergraphs.data.csv

import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure, framework-free CSV -> [ReadingEntity] parser. Shared by both import paths (SAF file
 * import and BLE reassembly funnel through this exact function) — see PROTOCOL.md for the
 * fixed wire format this must accept.
 *
 * Format (both sides of the wire contract must match):
 * ```
 * DATE,TIME,SPO2,PULSE
 * 2026-08-12, 19:15:38, 96, 75\r\n
 * ```
 * - Comma-space separators, but plain commas are tolerated too.
 * - CRLF line endings; bare `\n` must also be tolerated (line splitting handles both).
 * - No timezone information is carried by the format at all. Timestamps are parsed as
 *   [LocalDateTime] and converted to epoch seconds using the *device's current default
 *   zone* — this is a documented, inherent limitation of the fixed CSV/BLE format, not a
 *   bug: neither the CSV file nor the BLE transfer carries a zone offset.
 * - Malformed rows are skipped and counted, never fatal to the whole parse.
 */
object CsvParser {

    /**
     * The wire format's header row, in both directions: [parse] skips a line matching this
     * (case-insensitively), and [format]'s own caller
     * ([com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.exportCsv]) writes it
     * once up front — a single source of truth for the exact header text so import/export can't
     * quietly drift apart.
     */
    const val HEADER_LINE = "DATE,TIME,SPO2,PULSE"

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_TIME

    data class ParseResult(
        val readings: List<ReadingEntity>,
        val skippedRowCount: Int,
        val totalDataRowCount: Int,
    )

    /**
     * Parses [text] into readings using [zoneId] (defaults to the device's current default
     * zone) to convert local date/time to epoch seconds.
     */
    fun parse(text: String, zoneId: ZoneId = ZoneId.systemDefault()): ParseResult {
        if (text.isEmpty()) {
            return ParseResult(emptyList(), skippedRowCount = 0, totalDataRowCount = 0)
        }

        // Tolerate both CRLF and bare LF line endings.
        val lines = text.split("\r\n", "\n")

        val readings = mutableListOf<ReadingEntity>()
        var skipped = 0
        var totalDataRows = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.equals(HEADER_LINE, ignoreCase = true)) continue

            totalDataRows++
            val reading = parseRow(line, zoneId)
            if (reading != null) {
                readings.add(reading)
            } else {
                skipped++
            }
        }

        return ParseResult(readings, skippedRowCount = skipped, totalDataRowCount = totalDataRows)
    }

    /**
     * Formats [reading] as one CSV data row (no trailing line separator) — the export-side
     * inverse of [parseRow], used by
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.exportCsv]. [zoneId]
     * (defaulting to the device's current default zone, same as [parse]) converts the stored
     * epoch second back to a local date/time; since the format itself carries no zone info (see
     * this object's own doc), a file exported here and re-imported on a device set to a
     * *different* zone reads back shifted by the zone difference — there's no way around that
     * without changing the wire format itself. Plain `Instant -> ZonedDateTime -> LocalDateTime`
     * (not attempting to literally undo [parseRow]'s `LocalDateTime.atZone(zoneId)`) is
     * deliberate: that forward conversion can be ambiguous (a DST fall-back local time maps to
     * two different instants) or invalid (a DST spring-forward gap has no matching instant at
     * all), but going the other way, from a real stored instant back to local date/time, is
     * always exactly one answer.
     */
    fun format(reading: ReadingEntity, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val localDateTime = Instant.ofEpochSecond(reading.timestampEpochSec).atZone(zoneId).toLocalDateTime()
        val date = DATE_FORMATTER.format(localDateTime.toLocalDate())
        val time = TIME_FORMATTER.format(localDateTime.toLocalTime())
        return "$date, $time, ${reading.spo2}, ${reading.pulse}"
    }

    private fun parseRow(line: String, zoneId: ZoneId): ReadingEntity? {
        // Comma-space tolerant: split on a comma optionally followed by whitespace.
        val fields = line.split(Regex(",\\s*"))
        if (fields.size != 4) return null

        val (dateStr, timeStr, spo2Str, pulseStr) = fields

        val date: LocalDate = try {
            LocalDate.parse(dateStr.trim(), DATE_FORMATTER)
        } catch (e: DateTimeParseException) {
            return null
        }

        val time: LocalTime = try {
            LocalTime.parse(timeStr.trim(), TIME_FORMATTER)
        } catch (e: DateTimeParseException) {
            return null
        }

        val spo2 = spo2Str.trim().toIntOrNull() ?: return null
        val pulse = pulseStr.trim().toIntOrNull() ?: return null

        val epochSec = LocalDateTime.of(date, time).atZone(zoneId).toEpochSecond()

        return ReadingEntity(timestampEpochSec = epochSec, spo2 = spo2, pulse = pulse)
    }
}
