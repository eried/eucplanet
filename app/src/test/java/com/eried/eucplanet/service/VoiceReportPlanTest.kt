package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.VoiceReportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app says out loud, and in which order.
 *
 * A rider hears these while moving and cannot inspect them: a report that
 * silently stops being spoken is not noticed until the ride it mattered on. The
 * two ways that happens are a saved order that no longer matches the known
 * reports, and the periodic/trigger flags being read from the wrong setting.
 */
class VoiceReportPlanTest {

    private val defaults = AppSettings()

    // --- order -------------------------------------------------------------

    @Test fun `the rider's order is kept as they saved it`() {
        val order = VoiceReportPlan.order("Battery,Speed,Time")
        assertEquals(listOf("Battery", "Speed", "Time"), order.take(3))
    }

    @Test fun `reports the saved order predates are appended, never dropped`() {
        // An order saved before PhoneBattery existed. The setting for it can be
        // on, so it has to end up in the list, or it would never be spoken.
        val order = VoiceReportPlan.order("Speed,Battery,Time,Temp,PWM,Distance,Recording")
        assertTrue(order.contains("PhoneBattery"))
        assertTrue(order.contains("Navigation"))
        assertEquals(VoiceReportPlan.KNOWN.size, order.size)
    }

    @Test fun `names that are not reports are ignored`() {
        val order = VoiceReportPlan.order("Speed,Bananas,,Battery")
        assertFalse(order.contains("Bananas"))
        assertEquals(listOf("Speed", "Battery"), order.take(2))
    }

    @Test fun `a blank order still speaks everything, in the shipped order`() {
        assertEquals(VoiceReportPlan.KNOWN, VoiceReportPlan.order(""))
    }

    @Test fun `a duplicated name is only spoken once`() {
        val order = VoiceReportPlan.order("Speed,Speed,Battery")
        assertEquals(VoiceReportPlan.KNOWN.size, order.size)
        assertEquals(1, order.count { it == "Speed" })
    }

    @Test fun `every known report survives a round trip through the order`() {
        val saved = VoiceReportPlan.KNOWN.joinToString(",")
        assertEquals(VoiceReportPlan.KNOWN, VoiceReportPlan.order(saved))
    }

    // --- the two sets of switches -----------------------------------------

    @Test fun `periodic reads the periodic switches, trigger reads the trigger ones`() {
        val s = defaults.copy(
            voiceReports = VoiceReportSettings(
                periodicSpeed = true, triggerSpeed = false,
                periodicBattery = false, triggerBattery = true,
            )
        )
        assertTrue(VoiceReportPlan.isEnabled("Speed", s, periodic = true))
        assertFalse(VoiceReportPlan.isEnabled("Speed", s, periodic = false))
        assertFalse(VoiceReportPlan.isEnabled("Battery", s, periodic = true))
        assertTrue(VoiceReportPlan.isEnabled("Battery", s, periodic = false))
    }

    @Test fun `current and power come from the nested voice settings`() {
        val s = defaults.copy(
            voiceReports = VoiceReportSettings(
                periodicCurrent = true, periodicPower = false,
                triggerCurrent = false, triggerPower = true,
            )
        )
        assertTrue(VoiceReportPlan.isEnabled("Current", s, periodic = true))
        assertFalse(VoiceReportPlan.isEnabled("Power", s, periodic = true))
        assertFalse(VoiceReportPlan.isEnabled("Current", s, periodic = false))
        assertTrue(VoiceReportPlan.isEnabled("Power", s, periodic = false))
    }

    @Test fun `an unknown report is never enabled`() {
        assertFalse(VoiceReportPlan.isEnabled("Bananas", defaults, periodic = true))
        assertFalse(VoiceReportPlan.isEnabled("Bananas", defaults, periodic = false))
    }

    @Test fun `every known report has a switch on both sides`() {
        // Guards the pair of when() blocks: a report added to KNOWN without a
        // case falls through to false and is silently never spoken.
        val allOn = defaults.copy(
            voiceReports = VoiceReportSettings(
                periodicSpeed = true, periodicBattery = true, periodicPhoneBattery = true,
                periodicTemp = true, periodicPwm = true, periodicCurrent = true,
                periodicPower = true, periodicDistance = true, periodicRecording = true,
                periodicTime = true, periodicNavigation = true,
                triggerSpeed = true, triggerBattery = true, triggerPhoneBattery = true,
                triggerTemp = true, triggerPwm = true, triggerCurrent = true,
                triggerPower = true, triggerDistance = true, triggerRecording = true,
                triggerTime = true, triggerNavigation = true,
            ),
        )
        for (item in VoiceReportPlan.KNOWN) {
            assertTrue("$item has no periodic switch", VoiceReportPlan.isEnabled(item, allOn, true))
            assertTrue("$item has no trigger switch", VoiceReportPlan.isEnabled(item, allOn, false))
        }
    }

    // --- what actually gets spoken ----------------------------------------

    @Test fun `only switched-on reports are spoken, in the saved order`() {
        val s = defaults.copy(
            voiceReportOrder = "Battery,Speed,Temp",
            voiceReports = VoiceReportSettings(
                periodicSpeed = true, periodicBattery = true, periodicTemp = false,
                periodicPwm = false, periodicDistance = false, periodicRecording = false,
                periodicTime = false, periodicNavigation = false, periodicPhoneBattery = false,
            ),
        )
        assertEquals(listOf("Battery", "Speed"), VoiceReportPlan.items(s, periodic = true))
    }

    @Test fun `everything off says nothing at all`() {
        val silent = defaults.copy(
            voiceReports = VoiceReportSettings(
                periodicSpeed = false, periodicBattery = false, periodicPhoneBattery = false,
                periodicTemp = false, periodicPwm = false, periodicCurrent = false,
                periodicPower = false, periodicDistance = false, periodicRecording = false,
                periodicTime = false, periodicNavigation = false,
            ),
        )
        assertTrue(VoiceReportPlan.items(silent, periodic = true).isEmpty())
    }

    @Test fun `the shipped defaults speak speed and battery`() {
        // The out-of-the-box announcement a new rider hears.
        val spoken = VoiceReportPlan.items(defaults, periodic = true)
        assertTrue(spoken.contains("Speed"))
        assertTrue(spoken.contains("Battery"))
    }
}
