package com.eried.eucplanet.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

/** The dashboard's metric taps reach the same tabs on both phones. */
class MetricDetailRouteTest {

    @Test
    fun oneMetricIsOneTab() {
        assertEquals(MetricDetailRoute(listOf("BATTERY"), 0), MetricDetailRoute.parse("BATTERY"))
    }

    @Test
    fun aCompositeKeepsItsOrder() {
        assertEquals(
            MetricDetailRoute(listOf("SPEED", "BATTERY", "POWER"), 0),
            MetricDetailRoute.parse("SPEED, BATTERY,,POWER"),
        )
    }

    @Test
    fun aSideTapSelectsItsTab() {
        assertEquals(2, MetricDetailRoute.parse("SPEED,BATTERY,POWER|2").initialIndex)
        assertEquals(2, MetricDetailRoute.parse("SPEED,BATTERY,POWER|9").initialIndex)
        assertEquals(0, MetricDetailRoute.parse("SPEED,BATTERY|x").initialIndex)
    }
}
