package com.eried.eucplanet.hud.overlay

import com.eried.eucplanet.hud.protocol.HudState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD's metric list, held to the phone's.
 *
 * An overlay is built on the phone and drawn here, element by element, by
 * metric key. A key the HUD does not know falls back to SPEED, so a rider who
 * placed a range readout sees their speed in its place with nothing to say it
 * is wrong. The phone's enum is read from its source because the two modules
 * do not see each other.
 */
class StudioMetricKeysTest {

    private val phoneKeys: Set<String> = run {
        val src = File("../app/src/main/java/com/eried/eucplanet/ui/studio/StudioMetric.kt").readText()
        Regex("""(?m)^\s+[A-Z][A-Z0-9_]*\("([^"]+)",\s*"""")
            .findAll(src).map { it.groupValues[1] }.toSet()
    }

    @Test fun `the phone's keys were found`() {
        assertTrue("SPEED" in phoneKeys)
        assertTrue("G-FORCE" in phoneKeys)
        assertTrue(phoneKeys.size >= 20)
    }

    @Test fun `every phone metric has a HUD metric with the same key`() {
        val hudKeys = StudioMetric.entries.map { it.key }.toSet()
        assertEquals(emptySet<String>(), phoneKeys - hudKeys)
    }

    @Test fun `the HUD has no key the phone cannot send`() {
        val hudKeys = StudioMetric.entries.map { it.key }.toSet()
        assertEquals(emptySet<String>(), hudKeys - phoneKeys)
    }

    /** One frame as the phone sends it, through the same adapter the HUD uses. */
    private fun frame(hud: HudState) = StudioElementData.from(hud).wheelData

    @Test fun `energy, consumption, range and altitude read their own wire fields`() {
        val wd = frame(
            HudState(
                speedKmh = 25f,
                whConsumed = 412f,
                whPerKm = 18.6f,
                rangeKm = 37.4f,
                gpsAltitudeM = 1234f,
                torque = 42.5f,
            )
        )
        assertEquals("412", StudioMetric.fromKey("WH_CONSUMED").formatted(wd, "kmh", "km", "C"))
        assertEquals("18", StudioMetric.fromKey("WH_PER_KM").formatted(wd, "kmh", "km", "C"))
        assertEquals("37", StudioMetric.fromKey("RANGE_ESTIMATE").formatted(wd, "kmh", "km", "C"))
        assertEquals("1234", StudioMetric.fromKey("GPS_ALTITUDE").formatted(wd, "kmh", "km", "C"))
        assertEquals("42.5", StudioMetric.fromKey("TORQUE").formatted(wd, "kmh", "km", "C"))
    }

    @Test fun `a rider on miles reads the phone's numbers`() {
        val wd = frame(HudState(whPerKm = 18.6f, rangeKm = 37.4f, gpsAltitudeM = 1234f))
        // A rate converts the other way round: 18.6 Wh/km is 29.9 Wh/mi.
        assertEquals("29", StudioMetric.WH_PER_KM.formatted(wd, "mph", "mi", "F"))
        assertEquals("23", StudioMetric.RANGE_ESTIMATE.formatted(wd, "mph", "mi", "F"))
        assertEquals("4048", StudioMetric.GPS_ALTITUDE.formatted(wd, "mph", "mi", "F"))
    }

    @Test fun `nothing to say yet draws zero, never NaN`() {
        // HudState's defaults: whPerKm, rangeKm and gpsAltitudeM are NaN until
        // the phone has a reading, and an older phone never sends them.
        val wd = frame(HudState())
        assertEquals("0", StudioMetric.WH_CONSUMED.formatted(wd, "kmh", "km", "C"))
        assertEquals("0", StudioMetric.WH_PER_KM.formatted(wd, "kmh", "km", "C"))
        assertEquals("0", StudioMetric.RANGE_ESTIMATE.formatted(wd, "kmh", "km", "C"))
        assertEquals("0", StudioMetric.GPS_ALTITUDE.formatted(wd, "kmh", "km", "C"))
    }
}
