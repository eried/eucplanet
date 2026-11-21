package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot sheets enable "Restore slot" from [isMetricSlotAtDefault] /
 * [isActionSlotAtDefault], and the button writes [restoreDashboardMetricSlot] /
 * [restoreDashboardActionSlot]. The two must agree: a fresh install is at
 * default, any change the restore undoes reads as not-default, and the result
 * of a restore always reads as default.
 */
class DashboardSlotRestoreTest {

    private val fresh = AppSettings()

    @Test fun `fresh install has every active slot at default`() {
        for (i in 0 until ACTIVE_DASHBOARD_TILE_COUNT) {
            assertTrue("metric $i", isMetricSlotAtDefault(fresh, i))
            assertTrue("action $i", isActionSlotAtDefault(fresh, i))
        }
    }

    @Test fun `moved metric is not default and restore brings it back`() {
        val moved = fresh.copy(dashboardMetricOrder = "TEMPERATURE,BATTERY,VOLTAGE,CURRENT,LOAD,TRIP")
        assertFalse(isMetricSlotAtDefault(moved, 0))
        assertFalse(isMetricSlotAtDefault(moved, 1))
        assertTrue(isMetricSlotAtDefault(moved, 2))
        val restored = restoreDashboardMetricSlot(moved, 0)!!
        assertTrue(isMetricSlotAtDefault(restored, 0))
        assertTrue(isMetricSlotAtDefault(restored, 1))
    }

    @Test fun `changed stats on the natural occupant are not default`() {
        val stats = fresh.copy(dashboardMetricStats = """{"BATTERY":{"l":"AVG"}}""")
        assertFalse(isMetricSlotAtDefault(stats, 0))
        assertTrue(isMetricSlotAtDefault(restoreDashboardMetricSlot(stats, 0)!!, 0))

        val noSpark = fresh.copy(dashboardMetricStats = """{"BATTERY":{"spark":false}}""")
        assertFalse(isMetricSlotAtDefault(noSpark, 0))
        assertTrue(isMetricSlotAtDefault(restoreDashboardMetricSlot(noSpark, 0)!!, 0))
    }

    @Test fun `stats saved at their shipped values read as default`() {
        val same = fresh.copy(dashboardMetricStats = """{"BATTERY":{"l":"NONE","c":"CURRENT","spark":true}}""")
        assertTrue(isMetricSlotAtDefault(same, 0))
    }

    @Test fun `composite occupant is not default and restore removes it`() {
        val s = fresh.copy(
            dashboardMetricOrder = "M:abc,TEMPERATURE,VOLTAGE,CURRENT,LOAD,TRIP,BATTERY",
            dashboardCompositeMetrics = """{"M:abc":{"layout":"ROW2","cells":["SPEED","BATTERY"]}}"""
        )
        assertFalse(isMetricSlotAtDefault(s, 0))
        val restored = restoreDashboardMetricSlot(s, 0)!!
        assertTrue(isMetricSlotAtDefault(restored, 0))
        assertFalse(restored.dashboardCompositeMetrics.contains("M:abc"))
    }

    @Test fun `custom tile occupant is not default`() {
        val s = fresh.copy(
            dashboardMetricOrder = "BATTERY,C:t1,VOLTAGE,CURRENT,LOAD,TRIP,TEMPERATURE",
            dashboardCustomTiles = """{"C:t1":{"text":"hi"}}"""
        )
        assertFalse(isMetricSlotAtDefault(s, 1))
        assertTrue(isMetricSlotAtDefault(restoreDashboardMetricSlot(s, 1)!!, 1))
    }

    @Test fun `moved action and group occupant are not default`() {
        val swapped = fresh.copy(
            dashboardActionOrder = "LIGHT_TOGGLE,HORN,VOICE_ANNOUNCE,SAFETY_TOGGLE,LOCK_TOGGLE,RECORD_TOGGLE"
        )
        assertFalse(isActionSlotAtDefault(swapped, 0))
        assertTrue(isActionSlotAtDefault(swapped, 2))
        assertTrue(isActionSlotAtDefault(restoreDashboardActionSlot(swapped, 0)!!, 0))

        val group = fresh.copy(
            dashboardActionOrder = "G:g1,LIGHT_TOGGLE,VOICE_ANNOUNCE,SAFETY_TOGGLE,LOCK_TOGGLE,RECORD_TOGGLE,HORN",
            dashboardActionGroups = """{"G:g1":{"name":"x","actions":["HORN"]}}"""
        )
        assertFalse(isActionSlotAtDefault(group, 0))
        val restored = restoreDashboardActionSlot(group, 0)!!
        assertTrue(isActionSlotAtDefault(restored, 0))
        assertFalse(restored.dashboardActionGroups.contains("G:g1"))
    }

    @Test fun `empty slot sentinel is not default`() {
        val s = fresh.copy(dashboardMetricOrder = "BATTERY,$EMPTY_SLOT_KEY,VOLTAGE,CURRENT,LOAD,TRIP,TEMPERATURE")
        assertFalse(isMetricSlotAtDefault(s, 1))
        assertTrue(isMetricSlotAtDefault(restoreDashboardMetricSlot(s, 1)!!, 1))
    }

    @Test fun `restore on every slot of any layout reads as default afterwards`() {
        val shuffled = fresh.copy(
            dashboardMetricOrder = KNOWN_DASHBOARD_METRICS.reversed().joinToString(","),
            dashboardMetricStats = """{"BATTERY":{"r":"MAX"},"TRIP":{"c":"AVG"}}""",
            dashboardActionOrder = DEFAULT_DASHBOARD_ACTIONS.reversed().joinToString(",")
        )
        for (i in 0 until ACTIVE_DASHBOARD_TILE_COUNT) {
            assertFalse("metric $i", isMetricSlotAtDefault(shuffled, i))
            assertTrue("metric $i", isMetricSlotAtDefault(restoreDashboardMetricSlot(shuffled, i)!!, i))
            assertFalse("action $i", isActionSlotAtDefault(shuffled, i))
            assertTrue("action $i", isActionSlotAtDefault(restoreDashboardActionSlot(shuffled, i)!!, i))
        }
    }

    @Test fun `index with no shipped occupant has nothing to restore`() {
        val past = KNOWN_DASHBOARD_METRICS.size + 5
        assertTrue(isMetricSlotAtDefault(fresh, past))
        assertNull(restoreDashboardMetricSlot(fresh, past))
        assertTrue(isActionSlotAtDefault(fresh, DEFAULT_DASHBOARD_ACTIONS.size + 5))
    }
}
