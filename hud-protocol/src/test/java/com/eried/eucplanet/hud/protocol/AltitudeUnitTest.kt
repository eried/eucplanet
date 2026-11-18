package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A height follows the distance unit, for every unit the rider can pick. */
class AltitudeUnitTest {

    @Test fun `miles and feet read feet`() {
        assertTrue(AltitudeUnit.inFeet("mi"))
        // Custom units: a rider whose distances are in feet used to read metres.
        assertTrue(AltitudeUnit.inFeet("ft"))
        assertEquals("ft", AltitudeUnit.suffix("mi"))
        assertEquals("ft", AltitudeUnit.suffix("ft"))
    }

    @Test fun `kilometres, metres and the Norwegian mile read metres`() {
        for (unit in listOf("km", "m", "mil")) {
            assertFalse(unit, AltitudeUnit.inFeet(unit))
            assertEquals(unit, "m", AltitudeUnit.suffix(unit))
            assertEquals(unit, 120f, AltitudeUnit.value(120f, unit), 0f)
        }
    }

    @Test fun `a unit the app does not know reads metres`() {
        assertEquals("m", AltitudeUnit.suffix(""))
        assertEquals("m", AltitudeUnit.suffix("yd"))
    }

    @Test fun `feet are converted, metres are not`() {
        assertEquals(328.084f, AltitudeUnit.value(100f, "mi"), 0.001f)
        assertEquals(328.084f, AltitudeUnit.value(100f, "ft"), 0.001f)
        assertEquals(-32.8084f, AltitudeUnit.value(-10f, "mi"), 0.001f)
    }
}
