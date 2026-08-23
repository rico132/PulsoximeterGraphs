package com.oxipulse.pulsoximetergraphs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    /** REPLACE gives free dedupe: re-importing the same second overwrites, never duplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<ReadingEntity>)

    @Query(
        "SELECT * FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec " +
            "ORDER BY timestampEpochSec ASC"
    )
    fun observeRange(startEpochSec: Long, endEpochSec: Long): Flow<List<ReadingEntity>>

    @Query(
        "SELECT " +
            "MIN(spo2) AS minSpo2, MAX(spo2) AS maxSpo2, AVG(spo2) AS avgSpo2, " +
            "MIN(pulse) AS minPulse, MAX(pulse) AS maxPulse, AVG(pulse) AS avgPulse " +
            "FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec"
    )
    suspend fun statsForRange(startEpochSec: Long, endEpochSec: Long): ReadingStats

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    /**
     * Every already-stored timestamp within `[startEpochSec, endEpochSec]` — used by
     * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.importCsv] to drop
     * incoming rows that already exist rather than relying on [insertAll]'s REPLACE conflict
     * strategy to silently overwrite them, so a re-sync's "N rows synced" count reflects rows
     * actually new to this database, not every row the ESP32 happened to resend. A single range
     * scan against this table's own primary-key index, rather than one query per incoming
     * timestamp — immune to SQLite's ~999-bound-parameter ceiling that a plain
     * `WHERE timestampEpochSec IN (:allIncomingTimestamps)` would hit once a resync's CSV spans
     * more rows than that (see PROTOCOL.md: every `REQUEST_DATA` now resends the device's entire
     * history, not just what's new).
     */
    @Query(
        "SELECT timestampEpochSec FROM readings " +
            "WHERE timestampEpochSec BETWEEN :startEpochSec AND :endEpochSec"
    )
    suspend fun existingTimestampsInRange(startEpochSec: Long, endEpochSec: Long): List<Long>
}
