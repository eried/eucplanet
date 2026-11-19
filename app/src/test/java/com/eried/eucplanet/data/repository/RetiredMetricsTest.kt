package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredMetricsTest {

    @Test fun `a saved layout loses the retired tile and keeps the rest in order`() {
        val saved = AppSettings(dashboardMetricOrder = "BATTERY,HEADROOM,VOLTAGE,M:abc,TRIP")
        assertEquals("BATTERY,VOLTAGE,M:abc,TRIP", saved.sanitized().dashboardMetricOrder)
    }

    @Test fun `a layout without one is untouched`() {
        val order = "BATTERY,TEMPERATURE,VOLTAGE,CURRENT,LOAD,TRIP"
        assertEquals(order, AppSettings(dashboardMetricOrder = order).sanitized().dashboardMetricOrder)
    }

    /** A key cannot be both offered and cleaned out of every saved layout. */
    @Test fun `no retired key is still in the catalog`() {
        val live = MetricCatalog.all.map { it.key }.toSet()
        assertTrue("retired but still offered: ${RETIRED_METRIC_KEYS intersect live}",
            (RETIRED_METRIC_KEYS intersect live).isEmpty())
    }

    @Test fun `a stacked tile loses the retired cell and its statistic, and keeps the rest`() {
        val saved = """{"M:a":{"layout":"COL3","cells":["SPEED","HEADROOM","BATTERY"],"cellStats":["MAX","AVG","CURRENT"]},"M:b":{"layout":"ROW2","cells":["VOLTAGE","CURRENT"]}}"""
        val cleaned = org.json.JSONObject(withoutRetiredCells(saved))
        val a = cleaned.getJSONObject("M:a")
        assertEquals("""["SPEED","BATTERY"]""", a.getJSONArray("cells").toString())
        assertEquals("""["MAX","CURRENT"]""", a.getJSONArray("cellStats").toString())
        assertEquals("COL3", a.getString("layout"))
        assertEquals("""["VOLTAGE","CURRENT"]""", cleaned.getJSONObject("M:b").getJSONArray("cells").toString())
    }

    @Test fun `stacked tiles without one are returned as they were`() {
        val saved = """{"M:b":{"layout":"ROW2","cells":["VOLTAGE","CURRENT"]}}"""
        assertEquals(saved, withoutRetiredCells(saved))
        assertEquals("{}", withoutRetiredCells("{}"))
    }
}
