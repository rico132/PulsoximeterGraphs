package com.aternos.pulsoximetergraphs.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One second of pulse-oximeter measurement.
 *
 * The primary key is the reading's own timestamp (epoch seconds, in the device's default
 * timezone — see [com.aternos.pulsoximetergraphs.data.csv.CsvParser] for why no timezone is
 * carried). Using the timestamp as the PK plus [androidx.room.OnConflictStrategy.REPLACE] on
 * insert gives free de-duplication: re-importing a CSV file or re-syncing the same buffered
 * BLE rows overwrites in place instead of creating duplicate rows for the same second.
 */
@Entity(tableName = "readings")
data class ReadingEntity(
    @PrimaryKey val timestampEpochSec: Long,
    val spo2: Int,
    val pulse: Int,
)
