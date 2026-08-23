package com.oxipulse.pulsoximetergraphs.data.repository

import com.oxipulse.pulsoximetergraphs.data.csv.CsvParser
import com.oxipulse.pulsoximetergraphs.data.db.ReadingDao
import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import com.oxipulse.pulsoximetergraphs.data.db.ReadingStats
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The single funnel both import paths (SAF file import, BLE reassembly) and the UI go
 * through: [importCsv] parses+inserts, [observeRange]/[statsForRange] read back for display.
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

    fun observeRange(range: ClosedRange<Instant>): Flow<List<ReadingEntity>> =
        readingDao.observeRange(range.start.epochSecond, range.endInclusive.epochSecond)

    suspend fun statsForRange(range: ClosedRange<Instant>): ReadingStats =
        readingDao.statsForRange(range.start.epochSecond, range.endInclusive.epochSecond)
}
