package com.oxipulse.pulsoximetergraphs.data.db

/**
 * One chart-ready point: [reading] paired with [xIndex], its position on a coarse, fixed-width
 * time grid laid over the *actual data's own span* — not [reading]'s position in whatever list it
 * came back in, and not a position derived from the selected UI range. See
 * [com.oxipulse.pulsoximetergraphs.data.repository.ReadingsRepository.plottedReadings]'s own doc
 * for exactly how [xIndex] is computed.
 *
 * [xIndex] values are deliberately NOT contiguous when the underlying data has a real time gap —
 * two consecutive [PlottedReading]s in a sorted list can differ by more than 1. That's the whole
 * point: the chart plots by [xIndex], not by list position or raw timestamp. Vico (the charting
 * library) computes its horizontal pixel scale from the actual deltas between consecutive
 * x-values in a series, so a real gap in the data shows up as a proportionally wide gap on the
 * chart — instead of every pair of adjacent points rendering exactly as far apart as any other
 * pair regardless of how much real time actually separates them, which is what a plain "list
 * position as x" scheme gives no way to express.
 *
 * [sessionIndex] identifies which real, gap-separated "session" this reading belongs to (see
 * [ReadingDao.sessionBoundaries]) — 0 for the first session in the plotted range, incrementing by
 * one at every real gap. GraphScreen draws each distinct [sessionIndex] as its own separate line
 * segment, so the chart never draws a connecting line across a stretch where the device plainly
 * wasn't being worn — a wide [xIndex] gap alone only guarantees the *space* is proportionally
 * wide, not that no line gets drawn through it.
 */
data class PlottedReading(val xIndex: Long, val sessionIndex: Int, val reading: ReadingEntity)
