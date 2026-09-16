package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.model.VoiceReportSettings
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drift guard for the catalog-backed reports.
 *
 * Rule 13: a registry rather than per-item boilerplate, and a test over the
 * registry. Adding one of these used to mean editing five files, which is why
 * the list had not grown since PhoneBattery and why a rider asking for
 * estimated battery was told it was not worth the change. Now the risk moves:
 * a spec that names a metric the catalog does not have, or reuses a key, would
 * compile and then be silently unspeakable.
 */
class VoiceReportRegistryTest {

    @Test fun `every spec names a metric the catalog actually has`() {
        // The metric supplies the value, the spoken name and the formatting.
        // A typo here is a report that exists in settings, can be switched on,
        // and says nothing.
        for (spec in VoiceReportPlan.EXTRA) {
            assertNotNull(
                "${spec.key} names a metric the catalog does not have: ${spec.metricKey}",
                MetricCatalog.all.firstOrNull { it.key == spec.metricKey },
            )
        }
    }

    @Test fun `keys are unique and do not collide with the hand-written ones`() {
        // The key goes into voiceReportOrder, a comma-separated string a rider
        // drags into shape. A duplicate would make one of the pair unreachable
        // and the reorder ambiguous.
        val extras = VoiceReportPlan.EXTRA.map { it.key }
        assertEquals(extras.size, extras.distinct().size)
        val handWritten = listOf(
            "Speed", "Battery", "PhoneBattery", "Temp", "PWM",
            "Current", "Power", "Distance", "Recording", "Time", "Navigation",
        )
        assertTrue(
            "a catalog-backed key shadows a hand-written report",
            extras.none { it in handWritten },
        )
    }

    @Test fun `the registry is in KNOWN, so the order and the settings list see it`() {
        // KNOWN is read by the reorder, the settings matrix and the plan. A
        // spec outside it would have a toggle nowhere and never be spoken.
        for (spec in VoiceReportPlan.EXTRA) {
            assertTrue("${spec.key} missing from KNOWN", spec.key in VoiceReportPlan.KNOWN)
        }
    }

    @Test fun `KNOWN is built after EXTRA, not before it`() {
        // An object initialises its properties in source order. KNOWN reading
        // EXTRA from above would be the eleven and an empty list, and every
        // catalog-backed report would vanish without a compile error.
        assertTrue(VoiceReportPlan.KNOWN.size > 11)
        assertEquals(11 + VoiceReportPlan.EXTRA.size, VoiceReportPlan.KNOWN.size)
    }

    @Test fun `the new reports are off until a rider asks for them`() {
        // They are additions to an announcement riders already tuned. Arriving
        // switched on would make everyone's next ride five items longer
        // without anyone choosing that.
        val defaults = AppSettings()
        for (spec in VoiceReportPlan.EXTRA) {
            assertFalse("${spec.key} ships on (periodic)", spec.get(defaults.voiceReports, true))
            assertFalse("${spec.key} ships on (trigger)", spec.get(defaults.voiceReports, false))
        }
        assertTrue(VoiceReportPlan.items(defaults, periodic = true).none { it in VoiceReportPlan.EXTRA.map { e -> e.key } })
    }

    @Test fun `each setter writes its own side and leaves the other alone`() {
        // The periodic and trigger flags are configured differently on purpose
        // (a trigger tends to say everything, a periodic one only what matters
        // while moving). A setter that wrote both would quietly undo that.
        for (spec in VoiceReportPlan.EXTRA) {
            val onPeriodic = spec.set(VoiceReportSettings(), true, true)
            assertTrue("${spec.key} periodic did not take", spec.get(onPeriodic, true))
            assertFalse("${spec.key} periodic wrote the trigger too", spec.get(onPeriodic, false))

            val onTrigger = spec.set(VoiceReportSettings(), false, true)
            assertTrue("${spec.key} trigger did not take", spec.get(onTrigger, false))
            assertFalse("${spec.key} trigger wrote the periodic too", spec.get(onTrigger, true))
        }
    }

    @Test fun `a switched-on spec reaches the spoken list`() {
        // The whole path: the flag through isEnabled, through the order, into
        // what gets spoken.
        val spec = VoiceReportPlan.EXTRA.first { it.key == "BatteryEst" }
        val s = AppSettings().copy(
            voiceReports = spec.set(VoiceReportSettings(), true, true),
        )
        assertTrue(VoiceReportPlan.isEnabled("BatteryEst", s, periodic = true))
        assertTrue("BatteryEst" in VoiceReportPlan.items(s, periodic = true))
    }

    @Test fun `zero is nothing only where the packet uses it as its unset value`() {
        // WheelData is split: voltage and totalDistance default to 0f, so a
        // zero there is silence from the wheel. The other three default to
        // NaN, so a zero is a real reading, and "range, 0 miles" is the one a
        // rider most needs to hear. A blanket zero-is-missing rule swallowed
        // it, which is the bug this flag exists to have fixed.
        val expected = mapOf(
            "BatteryEst" to false, "Range" to false, "Consumption" to false,
            "Voltage" to true, "Odometer" to true,
        )
        for (spec in VoiceReportPlan.EXTRA) {
            assertEquals(
                "${spec.key} treats zero the wrong way",
                expected[spec.key], spec.blankAtZero,
            )
        }
    }

    @Test fun `the reader pulls a real field off the packet`() {
        // Each spec reads the wheel directly rather than going through a
        // second extractor map. A reader wired to the wrong field would report
        // one metric under another's name, which is the one mistake a spoken
        // value must never make: a rider hearing a number believes it.
        val data = WheelData(
            batteryEnvelope = 43f,
            rangeKmEstimate = 27f,
            voltage = 84.2f,
            totalDistance = 1234f,
            whPerKmRecent = 21f,
        )
        val read = VoiceReportPlan.EXTRA.associate { it.key to it.read(data) }
        assertEquals(43f, read["BatteryEst"])
        assertEquals(27f, read["Range"])
        assertEquals(84.2f, read["Voltage"])
        assertEquals(1234f, read["Odometer"])
        assertEquals(21f, read["Consumption"])
    }
}
