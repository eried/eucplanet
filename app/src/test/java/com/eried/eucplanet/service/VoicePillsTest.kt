package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pill lists that replace the per-report switches. A rider who never
 * opens the new editor must hear exactly what they heard before, and what
 * they build in it must survive being saved and read back.
 */
class VoicePillsTest {

    private val defaults = AppSettings()

    @Test fun `an untouched setup carries over from the switches, in order`() {
        for (periodic in listOf(true, false)) {
            assertEquals(
                VoiceReportPlan.items(defaults, periodic).map { VoicePill(it) },
                VoiceReportPlan.pills(defaults, periodic),
            )
        }
    }

    @Test fun `a saved list wins over the switches`() {
        val pills = listOf(VoicePill("Speed", VoicePill.Stat.MAX), VoicePill("Time"))
        val s = defaults.copy(voiceReports = defaults.voiceReports.copy(periodicPills = VoicePills.encode(pills)))
        assertEquals(pills, VoiceReportPlan.pills(s, periodic = true))
        // The other announcement keeps its own list.
        assertEquals(VoiceReportPlan.pills(defaults, periodic = false), VoiceReportPlan.pills(s, periodic = false))
    }

    @Test fun `an emptied list stays empty instead of falling back to the switches`() {
        val s = defaults.copy(voiceReports = defaults.voiceReports.copy(triggerPills = VoicePills.encode(emptyList())))
        assertTrue(VoiceReportPlan.pills(s, periodic = false).isEmpty())
    }

    @Test fun `repeats, statistics and messages round-trip`() {
        val pills = listOf(
            VoicePill("Speed"),
            VoicePill("Speed", VoicePill.Stat.MAX),
            VoicePill("PWM", VoicePill.Stat.PEAK),
            VoicePill(VoicePill.MESSAGE, text = "Drink water, then: check | tires"),
            VoicePill("Battery", VoicePill.Stat.MIN),
        )
        assertEquals(pills, VoicePills.decode(VoicePills.encode(pills)))
    }

    @Test fun `a statistic on a report with no history reads as now`() {
        assertEquals(listOf(VoicePill("Time")), VoicePills.decode("Time:MAX"))
    }

    @Test fun `unknown reports are dropped and unknown statistics read as now`() {
        assertEquals(listOf(VoicePill("Speed")), VoicePills.decode("Bananas:MAX|Speed:LOUDEST"))
    }

    @Test fun `a message longer than the limit is cut, a blank one dropped`() {
        val long = "x".repeat(VoicePill.MESSAGE_MAX + 20)
        val back = VoicePills.decode(VoicePills.encode(listOf(VoicePill(VoicePill.MESSAGE, text = long))))
        assertEquals(VoicePill.MESSAGE_MAX, back.single().text.length)
        assertTrue(VoicePills.decode("Message:").isEmpty())
    }

    @Test fun `a backup keeps the pill lists`() {
        val pills = listOf(VoicePill("Speed", VoicePill.Stat.MAX), VoicePill(VoicePill.MESSAGE, text = "Drink water"))
        val s = defaults.copy(voiceReports = defaults.voiceReports.copy(periodicPills = VoicePills.encode(pills)))
        val back = com.eried.eucplanet.data.store.SettingsJson.fromJson(
            org.json.JSONObject(com.eried.eucplanet.data.store.SettingsJson.toJson(s).toString())
        )
        assertEquals(pills, VoiceReportPlan.pills(back, periodic = true))
    }

    @Test fun `a backup from before the pills restores to the same announcement`() {
        // An older build's backup: switches and order, no pill keys at all.
        val old = org.json.JSONObject()
            .put("voiceReportOrder", "Battery,Speed,Time")
            .put("voiceReportSpeed", false).put("voiceReportBattery", true)
            .put("triggerReportTime", true)
        val s = com.eried.eucplanet.data.store.SettingsJson.fromJson(old)
        val periodic = VoiceReportPlan.pills(s, periodic = true).map { it.item }
        assertEquals("Battery", periodic.first())
        assertTrue("speed was switched off in that backup", "Speed" !in periodic)
        for (periodic in listOf(true, false)) {
            assertEquals(
                VoiceReportPlan.items(s, periodic).map { VoicePill(it) },
                VoiceReportPlan.pills(s, periodic),
            )
        }
    }

    @Test fun `catalog metrics are offered, phase amps among them`() {
        assertTrue("PHASE_CURRENT" in VoiceReportPlan.CATALOG)
        assertTrue("MOTOR_TEMP" in VoiceReportPlan.CATALOG)
        assertTrue("MOTOR_POWER" in VoiceReportPlan.CATALOG)
        // Never twice: speed already has its own report.
        assertTrue("SPEED" !in VoiceReportPlan.CATALOG)
        assertTrue("LAT_LONG" !in VoiceReportPlan.CATALOG)
    }

    @Test fun `every catalog metric is offered or left out on purpose`() {
        val all = com.eried.eucplanet.data.model.MetricCatalog.all.map { it.key }.toSet()
        val leftOut = all - VoiceReportPlan.CATALOG.toSet() -
            VoiceReportPlan.COVERED_BY_REPORT - VoiceReportPlan.NOT_SPOKEN
        // Nothing in the app reads these yet (the dashboard shows "--"), and a
        // word-valued mode is not a number to format. When one gains a source
        // it is offered by itself and this list must shrink.
        assertEquals(
            setOf(
                "TRIP_TIME", "TRIP_MAX_SPEED", "AVG_TRIP_SPEED", "HEADROOM", "SLOPE",
                "ASCENT", "DESCENT", "MOTOR_RPM", "PC_MODE",
            ),
            leftOut,
        )
    }

    @Test fun `catalog pills round-trip and take statistics only with history`() {
        val pills = listOf(
            VoicePill("#PHASE_CURRENT", VoicePill.Stat.MAX),
            VoicePill("#TRIP_METER"),
        )
        assertEquals(pills, VoicePills.decode(VoicePills.encode(pills)))
        assertEquals("PHASE_CURRENT", VoiceReportPlan.statKey("#PHASE_CURRENT"))
        assertEquals(null, VoiceReportPlan.statKey("#TRIP_METER"))
        assertEquals(listOf(VoicePill("#TRIP_METER")), VoicePills.decode("#TRIP_METER:MAX"))
        assertTrue(VoicePills.decode("#NOT_A_METRIC:NOW").isEmpty())
    }

    @Test fun `every report with history maps to a catalog key that keeps stats`() {
        val catalog = com.eried.eucplanet.data.model.MetricCatalog.all.associateBy { it.key }
        VoiceReportPlan.KNOWN.mapNotNull { VoiceReportPlan.statKey(it) }.forEach { key ->
            val spec = catalog[key]
            assertTrue("$key is not in the metric catalog", spec != null)
            assertTrue("$key keeps no stats history", spec!!.supportsStats)
        }
    }
}
