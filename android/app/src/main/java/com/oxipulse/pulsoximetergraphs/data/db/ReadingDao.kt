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
}
