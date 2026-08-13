package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Altitude is the one Studio metric held in metres rather than a wheel unit,
 * so its conversion is its own path and worth pinning down. The emulator can
 * inject an altitude, but only a test says what the rider actually reads.
 */
class StudioMetricAltitudeTest {

    private val metric = StudioMetric.GPS_ALTITUDE

    private fun dataAt(altitudeM: Float) = WheelData(gpsAltitudeM = altitudeM)

    @Test
    fun `metres for a rider on kilometres`() {
        assertEquals("1234", metric.formatted(dataAt(1234f), "kmh", "km", "C"))
    }

    @Test
    fun `feet for a rider on miles`() {
        // 1234 m is 4048.6 ft; the metric shows no decimals.
        assertEquals("4048", metric.formatted(dataAt(1234f), "mph", "mi", "F"))
    }

    @Test
    fun `below sea level survives, it is a reading and not an error`() {
        // The Dead Sea shore is about -430 m. A -1 sentinel would have made
        // this indistinguishable from "no fix", which is why the field is NaN
        // when absent rather than negative.
        assertEquals("-430", metric.formatted(dataAt(-430f), "kmh", "km", "C"))
    }

    @Test
    fun `no fix reads as zero rather than crashing on NaN`() {
        // WheelData's default: no fix yet. NaN through toInt() would be 0
        // anyway, but the extractor makes that explicit rather than incidental.
        assertEquals(Float.NaN, WheelData().gpsAltitudeM)
        assertEquals("0", metric.formatted(WheelData(), "kmh", "km", "C"))
    }

    @Test
    fun `the unit label follows the distance unit`() {
        assertEquals(StudioMetricKind.ALTITUDE, metric.kind)
        // unitText needs a Context for speed/temp units, so the altitude branch
        // is asserted through displayValue instead: feet is 3.28084x metres.
        assertEquals(100f, metric.displayValue(dataAt(100f), "kmh", "km", "C"), 0.01f)
        assertEquals(328.084f, metric.displayValue(dataAt(100f), "mph", "mi", "F"), 0.01f)
    }
}
