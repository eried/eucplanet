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
}
