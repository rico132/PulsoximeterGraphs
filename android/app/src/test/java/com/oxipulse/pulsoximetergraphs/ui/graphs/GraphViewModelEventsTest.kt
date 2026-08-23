package com.oxipulse.pulsoximetergraphs.ui.graphs

import com.oxipulse.pulsoximetergraphs.data.db.ReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `pulse` is irrelevant to [countSpo2Events] — always 70, purely to satisfy the constructor. */
private fun reading(epochSec: Long, spo2: Int) = ReadingEntity(epochSec, spo2, 70)

/**
 * The gap [countSpo2Events] measures is between a run's *last* below-threshold reading and the
 * *next* run's first below-threshold reading — not from whatever above-threshold reading sits in
 * between. Every test below anchors its timestamps off that last-below-threshold reading (always
 * at epoch 0) to keep that unambiguous.
 */
class GraphViewModelEventsTest {

    @Test
    fun `no readings is null, not zero`() {
        assertNull(countSpo2Events(emptyList()))
    }

    @Test
    fun `no reading below threshold is zero events`() {
        val readings = listOf(reading(0, 96), reading(1, 98), reading(2, 100))
        assertEquals(0, countSpo2Events(readings))
    }

    @Test
    fun `a single dip is one event`() {
        val readings = listOf(reading(0, 96), reading(1, 90), reading(2, 91), reading(3, 96))
        assertEquals(1, countSpo2Events(readings))
    }

    @Test
    fun `two dips separated by a long recovery are two events`() {
        val readings = listOf(
            reading(0, 90), // last below-threshold reading of the first run
            reading(1, 96), // recovers...
            reading(EVENT_MERGE_GAP_SECONDS + 1, 90), // ...for longer than the merge gap
        )
        assertEquals(2, countSpo2Events(readings))
    }

    @Test
    fun `two dips separated by a brief recovery merge into one event`() {
        val readings = listOf(
            reading(0, 90), // last below-threshold reading of the first run
            reading(1, 96), // a brief bounce back above threshold...
            reading(EVENT_MERGE_GAP_SECONDS - 1, 90), // ...shorter than the merge gap
        )
        assertEquals(1, countSpo2Events(readings))
    }

    @Test
    fun `a recovery of exactly the merge gap does not merge`() {
        // "less than" EVENT_MERGE_GAP_SECONDS merges; exactly EVENT_MERGE_GAP_SECONDS does not.
        val readings = listOf(
            reading(0, 90),
            reading(1, 96),
            reading(EVENT_MERGE_GAP_SECONDS, 90),
        )
        assertEquals(2, countSpo2Events(readings))
    }

    @Test
    fun `three dips with one long and one short gap between them is two events`() {
        val readings = listOf(
            reading(0, 90),
            reading(1, 96),
            reading(EVENT_MERGE_GAP_SECONDS - 1, 91), // merges with the first dip
            reading(EVENT_MERGE_GAP_SECONDS, 97),
            reading(EVENT_MERGE_GAP_SECONDS * 10, 89), // long gap — a genuinely new event
        )
        assertEquals(2, countSpo2Events(readings))
    }

    @Test
    fun `a run entirely below threshold with no recovery at all is one event`() {
        val readings = listOf(reading(0, 90), reading(1, 88), reading(2, 91), reading(3, 93))
        assertEquals(1, countSpo2Events(readings))
    }
}
