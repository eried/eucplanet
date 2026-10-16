package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.hud.protocol.MapLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reached stops are history: a rider who added a stop after arriving got a
 * "dead" flag beside the new stop and could reorder the new one around it.
 */
class FinishedRouteRulesTest {

    private fun wp(name: String, passed: Boolean = false) = Waypoint(0.0, 0.0, name, passed = passed)

    @Test fun `reached stops are the leading block`() {
        assertEquals(0, passedPrefix(listOf(wp("a"), wp("b"))))
        assertEquals(2, passedPrefix(listOf(wp("a", true), wp("b", true), wp("c"))))
        assertEquals(2, passedPrefix(listOf(wp("a", true), wp("b", true))))
        assertEquals(0, passedPrefix(emptyList()))
    }

    @Test fun `a new stop cannot be moved in front of a reached one`() {
        val list = listOf(wp("reached", true), wp("next"), wp("new"))
        val moved = reorderKeepingPassed(list, from = 2, to = 0)!!
        assertEquals(listOf("reached", "new", "next"), moved.map { it.name })
    }

    @Test fun `a reached stop cannot be moved at all`() {
        val list = listOf(wp("reached", true), wp("next"))
        assertNull(reorderKeepingPassed(list, from = 0, to = 1))
    }

    @Test fun `ordinary reordering is unchanged`() {
        val list = listOf(wp("a"), wp("b"), wp("c"))
        assertEquals(listOf("c", "a", "b"), reorderKeepingPassed(list, 2, 0)!!.map { it.name })
        assertNull(reorderKeepingPassed(list, 5, 0))
    }

    @Test fun `satellite plus labels draws imagery, road names and place names`() {
        val hybrid = MapLayers.byId(MapLayers.SATELLITE_LABELS)
        assertEquals(MapLayers.SATELLITE_LABELS, hybrid.id)
        assertEquals(MapLayers.byId(MapLayers.SATELLITE).urlTemplate, hybrid.urlTemplate)
        assertTrue(hybrid.refUrlTemplate.contains("Reference/World_Transportation"))
        assertTrue(hybrid.extraRefUrlTemplate.contains("Reference/World_Boundaries_and_Places"))
        // OSM data sits under the Esri reference layers, so it is credited too.
        assertTrue(hybrid.attribution.contains("OpenStreetMap"))
        // The picker offers it, and both Leaflet maps receive the second layer.
        assertTrue(RouteBuilderViewModel.MAP_LAYERS.any { it.id == MapLayers.SATELLITE_LABELS })
        assertTrue(mapLayersJson().contains("ref2:'https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places"))
    }
}
