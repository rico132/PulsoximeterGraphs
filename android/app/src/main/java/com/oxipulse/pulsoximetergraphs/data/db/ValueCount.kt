package com.oxipulse.pulsoximetergraphs.data.db

/**
 * One (value, count) bucket of a histogram over a single integer column (SpO2 or pulse) — see
 * [ReadingDao.spo2Histogram]/[ReadingDao.pulseHistogram]. Column names must match exactly what
 * those queries alias their `GROUP BY` result columns to.
 */
data class ValueCount(val value: Int, val count: Int)
