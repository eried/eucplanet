package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Heights have their own unit; a rider who never chose one keeps what the
 *  distance unit gave them. */
class AltitudeUnitTest {

    private val distances = listOf("km", "mi", "m", "ft", "mil")

    @Test fun `the rider's choice wins over every distance unit`() {
        for (d in distances) {
            assertEquals(d, "m", AltitudeUnit.resolve("m", d))
            assertEquals(d, "ft", AltitudeUnit.resolve("ft", d))
        }
    }

    @Test fun `miles with heights in metres, the UK case`() {
        assertEquals("m", AltitudeUnit.resolve("m", "mi"))
        assertEquals(120f, AltitudeUnit.value(120f, AltitudeUnit.resolve("m", "mi")), 0f)
    }

    @Test fun `kilometres with heights in feet`() {
        assertEquals("ft", AltitudeUnit.resolve("ft", "km"))
        assertEquals(328.084f, AltitudeUnit.value(100f, AltitudeUnit.resolve("ft", "km")), 0.001f)
    }

    @Test fun `no choice yet follows the distance unit`() {
        assertEquals("ft", AltitudeUnit.resolve("", "mi"))
        // Distances in feet used to read heights in metres.
        assertEquals("ft", AltitudeUnit.resolve("", "ft"))
        for (d in listOf("km", "m", "mil")) assertEquals(d, "m", AltitudeUnit.resolve("", d))
    }

    @Test fun `anything that is not a height unit counts as no choice`() {
        assertEquals("m", AltitudeUnit.resolve("yd", "km"))
        assertEquals("ft", AltitudeUnit.resolve("km", "mi"))
        assertEquals("m", AltitudeUnit.resolve("", ""))
    }

    @Test fun `feet are converted, metres are not`() {
        assertEquals(328.084f, AltitudeUnit.value(100f, "ft"), 0.001f)
        assertEquals(-32.8084f, AltitudeUnit.value(-10f, "ft"), 0.001f)
        assertEquals(100f, AltitudeUnit.value(100f, "m"), 0f)
    }
}
