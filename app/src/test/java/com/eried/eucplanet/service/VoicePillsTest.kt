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

    @Test fun `every report with history maps to a catalog key that keeps stats`() {
        val catalog = com.eried.eucplanet.data.model.MetricCatalog.all.associateBy { it.key }
        VoiceReportPlan.KNOWN.mapNotNull { VoiceReportPlan.statKey(it) }.forEach { key ->
            val spec = catalog[key]
            assertTrue("$key is not in the metric catalog", spec != null)
            assertTrue("$key keeps no stats history", spec!!.supportsStats)
        }
    }
}
