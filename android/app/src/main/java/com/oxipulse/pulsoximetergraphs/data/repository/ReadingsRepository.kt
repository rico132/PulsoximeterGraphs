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
     * Parses [csvText] with [CsvParser] and inserts every valid row (REPLACE-dedup'd by
     * timestamp). Returns the parse result so callers (e.g. [com.oxipulse.pulsoximetergraphs.data.ble.BleGattClient])
     * know it's safe to proceed (e.g. write CLEAR_BUFFER) only once this suspend call returns.
     */
    suspend fun importCsv(csvText: String): CsvParser.ParseResult {
        // CsvParser.parse is plain CPU-bound work (regex split + date/time parsing per row), so
        // on Dispatchers.Main.immediate (the default for callers using viewModelScope) a large
        // file would otherwise freeze the UI for the whole parse instead of just showing a
        // progress state.
        val result = withContext(Dispatchers.Default) { CsvParser.parse(csvText) }
        if (result.readings.isNotEmpty()) {
            readingDao.insertAll(result.readings)
        }
        return result
    }

    fun observeRange(range: ClosedRange<Instant>): Flow<List<ReadingEntity>> =
        readingDao.observeRange(range.start.epochSecond, range.endInclusive.epochSecond)

    suspend fun statsForRange(range: ClosedRange<Instant>): ReadingStats =
        readingDao.statsForRange(range.start.epochSecond, range.endInclusive.epochSecond)
}
