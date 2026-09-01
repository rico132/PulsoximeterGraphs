package com.oxipulse.pulsoximetergraphs.settings

import com.oxipulse.pulsoximetergraphs.data.settings.ThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdConfigTest {

    @Test
    fun `defaults from PROTOCOL md are valid`() {
        assertNull(ThresholdConfig.DEFAULT.validate())
        assertTrue(ThresholdConfig.DEFAULT.isValid())
    }

    @Test
    fun `defaults match PROTOCOL md verbatim`() {
        val config = ThresholdConfig()
        assertEquals(95, config.spo2Orange)
        assertEquals(90, config.spo2Red)
        assertEquals(50, config.pulseLowOrange)
        assertEquals(45, config.pulseLowRed)
        assertEquals(90, config.pulseHighOrange)
        assertEquals(100, config.pulseHighRed)
        assertEquals(90, config.spo2EventThreshold)
    }

    @Test
    fun `spo2Red must be lower than spo2Orange`() {
        val config = ThresholdConfig.DEFAULT.copy(spo2Red = 95, spo2Orange = 95)
        assertNotNull(config.validate())
    }

    @Test
    fun `pulseLowRed must be lower than pulseLowOrange`() {
        val config = ThresholdConfig.DEFAULT.copy(pulseLowRed = 50, pulseLowOrange = 50)
        assertNotNull(config.validate())
    }

    @Test
    fun `pulseLowOrange must be lower than pulseHighOrange`() {
        val config = ThresholdConfig.DEFAULT.copy(pulseLowOrange = 100, pulseHighOrange = 100)
        assertNotNull(config.validate())
    }

    @Test
    fun `pulseHighOrange must be lower than pulseHighRed`() {
        val config = ThresholdConfig.DEFAULT.copy(pulseHighOrange = 120, pulseHighRed = 120)
        assertNotNull(config.validate())
    }

    @Test
    fun `a config with all orderings strictly satisfied is valid`() {
        val config = ThresholdConfig(
            spo2Orange = 94,
            spo2Red = 88,
            pulseLowOrange = 55,
            pulseLowRed = 40,
            pulseHighOrange = 110,
            pulseHighRed = 130,
        )
        assertNull(config.validate())
    }
}
