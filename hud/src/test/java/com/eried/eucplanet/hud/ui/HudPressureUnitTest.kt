package com.eried.eucplanet.hud.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The HUD's pressure conversions, pinned against the phone's.
 *
 * The HUD keeps its own copy of the unit maths - the phone's version reaches
 * for string resources the HUD has no use for - and a copy with no test is a
 * copy that drifts. These numbers are the phone's
 * `Units.pressure` / `pressureUnit` / `pressureDecimals` for the same inputs,
 * so if either side changes a constant, one of the two suites goes red.
 *
 * The HUD used to derive the unit from the DISTANCE unit, which meant a rider
 * on kilometres who runs psi in the tyre read bar on their glasses and psi on
 * the phone in the same glance.
 */
class HudPressureUnitTest {

    /** 3.6 bar, the pressure the sensor decode was confirmed against. */
    private val kpa = 360f

    @Test fun `every unit converts the way the phone converts`() {
        assertEquals(3.60f, HudUnits.pressure(kpa, "bar"), 0.001f)
        assertEquals(52.2f, HudUnits.pressure(kpa, "psi"), 0.05f)
        assertEquals(360f, HudUnits.pressure(kpa, "kpa"), 0.001f)
        assertEquals(3.67f, HudUnits.pressure(kpa, "kgf"), 0.01f)
        assertEquals(0.36f, HudUnits.pressure(kpa, "mpa"), 0.001f)
    }

    @Test fun `every unit is named the way the phone names it`() {
        assertEquals("bar", HudUnits.pressureSuffix("bar"))
        assertEquals("psi", HudUnits.pressureSuffix("psi"))
        assertEquals("kPa", HudUnits.pressureSuffix("kpa"))
        assertEquals("kgf/cm²", HudUnits.pressureSuffix("kgf"))
        assertEquals("MPa", HudUnits.pressureSuffix("mpa"))
    }

    @Test fun `an unknown code reads as bar, which is what a stale phone sends`() {
        // A phone older than this protocol sends no unit at all, and the
        // default has to be what the HUD has always shown rather than a
        // hundred-fold jump into kPa.
        assertEquals(3.60f, HudUnits.pressure(kpa, ""), 0.001f)
        assertEquals("bar", HudUnits.pressureSuffix(""))
    }

    @Test fun `psi is floored, matching the wheel's own display`() {
        // 210 kPa is 30.458 psi. The wheel truncates; rounding here would read
        // 30.5 beside the wheel's 30.4.
        assertEquals(30.4f, HudUnits.pressure(210f, "psi"), 0.001f)
    }

    @Test fun `decimals follow the unit, so MPa can show a pump working`() {
        assertEquals(1, HudUnits.pressureDecimals("psi"))
        assertEquals(2, HudUnits.pressureDecimals("bar"))
        assertEquals(2, HudUnits.pressureDecimals("kgf"))
        assertEquals(3, HudUnits.pressureDecimals("mpa"))
        assertEquals(0, HudUnits.pressureDecimals("kpa"))

        // A whole bar is a tenth of an MPa: at two decimals a rider letting
        // air out by half a bar would barely see the number move.
        val soft = "%.${HudUnits.pressureDecimals("mpa")}f".format(HudUnits.pressure(340f, "mpa"))
        val hard = "%.${HudUnits.pressureDecimals("mpa")}f".format(HudUnits.pressure(350f, "mpa"))
        assertNotEquals(soft, hard)
    }
}
