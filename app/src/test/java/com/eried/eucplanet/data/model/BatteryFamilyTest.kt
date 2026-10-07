package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery and Battery (est) are one family.
 *
 * They measure the same quantity two ways: the raw percentage the wheel
 * reports, which sags under load, and the envelope with the load taken out. A
 * rider thinks of that as one thing with two readings, so the rule list says
 * so, the voice says so, and only the metric picker distinguishes them.
 */
class BatteryFamilyTest {

    @Test fun `both battery metrics are filed under one family`() {
        assertEquals(AlarmMetric.BATTERY.groupKey, AlarmMetric.BATTERY_ENVELOPE.groupKey)
        assertEquals("BATTERY", AlarmMetric.BATTERY_ENVELOPE.groupKey)
    }

    @Test fun `grouping by family puts them in the same bucket`() {
        val rules = listOf(
            AlarmRule(metric = AlarmMetric.BATTERY.name, threshold = 20f),
            AlarmRule(metric = AlarmMetric.BATTERY_ENVELOPE.name, threshold = 30f),
            AlarmRule(metric = AlarmMetric.SPEED.name, threshold = 30f),
        )
        val families = rules.groupBy { AlarmMetric.valueOf(it.metric).groupKey }
        assertEquals(2, families.size)
        assertEquals(2, families.getValue("BATTERY").size)
    }

    @Test fun `every other metric is its own family`() {
        // The mechanism must not quietly merge anything else.
        val shared = AlarmMetric.entries.filter { it.groupOf != null }
        assertEquals(listOf(AlarmMetric.BATTERY_ENVELOPE), shared)
        for (m in AlarmMetric.entries.filter { it.groupOf == null }) {
            assertEquals(m.name, m.groupKey)
        }
    }

    @Test fun `a shared family always names a real metric`() {
        // The heading is drawn from whichever metric heads the family, so a
        // groupOf pointing at nothing would render as SPEED.
        for (m in AlarmMetric.entries) {
            val key = m.groupKey
            assertTrue(
                "family $key has no metric to name it",
                AlarmMetric.entries.any { it.name == key },
            )
        }
    }

    @Test fun `the family sorts together, so auto-sort cannot split it`() {
        // BATTERY_ENVELOPE sits directly after BATTERY in the enum. Auto-sort
        // orders by enum position, and with the two apart it interleaved
        // other metrics between them.
        val order = AlarmMetric.entries.map { it.name }
        val i = order.indexOf("BATTERY")
        assertEquals("BATTERY_ENVELOPE", order[i + 1])
    }

    @Test fun `the picker spells it out and the field keeps it short`() {
        assertNotEquals(
            "the long and short labels are the same string",
            AlarmMetric.BATTERY_ENVELOPE.labelRes,
            AlarmMetric.BATTERY_ENVELOPE.longLabelRes,
        )
        // Everything else uses one name for both, which is the default.
        for (m in AlarmMetric.entries - AlarmMetric.BATTERY_ENVELOPE) {
            assertEquals(m.labelRes, m.longLabelRes)
        }
    }

    @Test fun `voice reads it as battery`() {
        // The reading IS the battery; the envelope is how it was measured, and
        // a rider does not want "estimate" read at them on every announcement.
        assertEquals(AlarmMetric.BATTERY.labelRes, AlarmMetric.BATTERY_ENVELOPE.voiceLabelRes)
    }

    @Test fun `it is watched from below, like the battery it belongs to`() {
        assertEquals(AlarmComparator.LESS_THAN, AlarmMetric.BATTERY_ENVELOPE.defaultComparator)
        assertEquals("%", AlarmMetric.BATTERY_ENVELOPE.unit)
    }
}
