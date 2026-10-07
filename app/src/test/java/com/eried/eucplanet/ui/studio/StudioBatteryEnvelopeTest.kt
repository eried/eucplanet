package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The load-free battery line as an overlay element.
 *
 * It is the one Studio metric whose absence is a real state rather than a
 * missing sensor: for the first half minute of every ride there is no
 * envelope yet, because it takes that long to have anything to say. A dial
 * still has to draw something, and the bottom of the scale is where the other
 * not-yet metrics (Consumption, Range, Altitude) put it.
 */
class StudioBatteryEnvelopeTest {

    private val metric = StudioMetric.BATTERY_ENVELOPE

    private fun at(envelope: Float) = WheelData(batteryEnvelope = envelope)

    @Test fun `it reads as a whole percent, like the battery beside it`() {
        assertEquals("71", metric.formatted(at(71.4f), "kmh", "km", "C"))
        assertEquals("%", metric.plainUnit)
    }

    @Test fun `no unit conversion touches it, on any unit system`() {
        // A percentage is a percentage. Imperial must not turn it into
        // anything else, which is what a wrong `kind` would do.
        assertEquals("71", metric.formatted(at(71.4f), "mph", "mi", "F"))
    }

    @Test fun `nothing to say yet draws the bottom of the scale, not NaN`() {
        // The first half minute of a ride. Printing "NaN" on a rider's own
        // overlay, in a recording they keep, is the failure this prevents.
        assertEquals("0", metric.formatted(at(Float.NaN), "kmh", "km", "C"))
        assertEquals(0f, metric.displayValue(at(Float.NaN), "kmh", "km", "C"), 0.001f)
    }

    @Test fun `a flat pack is a reading, not an absence`() {
        assertEquals("0", metric.formatted(at(0f), "kmh", "km", "C"))
    }

    @Test fun `the gauge scale is a percentage scale`() {
        assertEquals(100f, metric.defaultMax, 0.001f)
    }

    @Test fun `its key matches the dashboard's, so one rider learns one name`() {
        assertEquals("BATTERY_ENVELOPE", metric.key)
    }
}
