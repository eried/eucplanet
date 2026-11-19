package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two corners of the ride page: what the rider picked, as ASCII. */
class EngoMetricsTest {

    private fun cell(key: String) = EngoMetrics.cell(
        key, batteryPct = 78, temp = 38, tempUnit = "C",
        voltage = 83.46f, current = 12.6f, trip = 4.25f, distanceUnit = "km",
    )

    @Test fun `each pick has a tag and a value`() {
        assertEquals(EngoCell("BATT", "78%"), cell(EngoMetrics.BATTERY))
        assertEquals(EngoCell("TEMP", "38C"), cell(EngoMetrics.TEMPERATURE))
        assertEquals(EngoCell("VOLT", "83.5V"), cell(EngoMetrics.VOLTAGE))
        assertEquals(EngoCell("AMPS", "13A"), cell(EngoMetrics.CURRENT))
        assertEquals(EngoCell("TRIP", "4.3km"), cell(EngoMetrics.TRIP))
    }

    @Test fun `none leaves the corner empty`() {
        assertEquals(EngoCell.EMPTY, cell(EngoMetrics.NONE))
    }

    @Test fun `a key the glasses cannot show falls back`() {
        assertEquals(EngoMetrics.BATTERY, EngoMetrics.known("PITCH", EngoMetrics.BATTERY))
        assertEquals(EngoMetrics.NONE, EngoMetrics.known(EngoMetrics.NONE, EngoMetrics.BATTERY))
        assertEquals(EngoMetrics.TRIP, EngoMetrics.known(EngoMetrics.TRIP, EngoMetrics.BATTERY))
    }

    @Test fun `every pick fits the ASCII the glasses draw`() {
        for (key in EngoMetrics.KEYS) {
            val c = cell(key)
            assertEquals(true, (c.tag + c.value).all { it.code in 32..126 })
        }
    }
}
