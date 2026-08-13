package com.aternos.pulsoximetergraphs.data.repository

import com.aternos.pulsoximetergraphs.data.csv.CsvParser
import com.aternos.pulsoximetergraphs.data.db.ReadingDao
import com.aternos.pulsoximetergraphs.data.db.ReadingEntity
import com.aternos.pulsoximetergraphs.data.db.ReadingStats
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The single funnel both import paths (SAF file import, BLE reassembly) and the UI go
 * through: [importCsv] parses+inserts, [observeRange]/[statsForRange] read back for display.
 */
class ReadingsRepository(private val readingDao: ReadingDao) {

    /**
     * Parses [csvText] with [CsvParser] and inserts every valid row (REPLACE-dedup'd by
     * timestamp). Returns the parse result so callers (e.g. [com.aternos.pulsoximetergraphs.data.ble.BleGattClient])
     * know it's safe to proceed (e.g. write CLEAR_BUFFER) only once this suspend call returns.
     */
    suspend fun importCsv(csvText: String): CsvParser.ParseResult {
        val result = CsvParser.parse(csvText)
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
