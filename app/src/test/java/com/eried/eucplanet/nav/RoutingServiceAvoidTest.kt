package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic guards for the Valhalla avoidance backend: the none/any gate, the
 * costing-options JSON the avoidances translate to per travel mode, and the
 * Valhalla maneuver-type mapping. No network.
 */
class RoutingServiceAvoidTest {

    @Test fun avoidances_noneAndAny() {
        assertTrue(RouteAvoidances.NONE.none)
        assertFalse(RouteAvoidances.NONE.any)
        assertTrue(RouteAvoidances(ferries = true).any)
        assertFalse(RouteAvoidances(ferries = true).none)
    }

    @Test fun costing_perTravelMode() {
        assertEquals("auto", RoutingService.valhallaCosting(TravelMode.DRIVING))
        assertEquals("pedestrian", RoutingService.valhallaCosting(TravelMode.WALKING))
        assertEquals("bicycle", RoutingService.valhallaCosting(TravelMode.CYCLING))
    }

    @Test fun costingOptions_auto_appliesHighwaysTollsFerries() {
        val opts = RoutingService.valhallaCostingOptions(
            "auto", RouteAvoidances(highways = true, tolls = true, ferries = true)
        )!!.getJSONObject("auto")
        assertEquals(0, opts.getInt("use_highways"))
        assertEquals(0, opts.getInt("use_tolls"))
        assertEquals(0, opts.getInt("use_ferry"))
    }

    @Test fun costingOptions_bicycle_appliesFerriesAndUnpaved_notHighwaysTolls() {
        val opts = RoutingService.valhallaCostingOptions(
            "bicycle", RouteAvoidances(ferries = true, unpaved = true)
        )!!.getJSONObject("bicycle")
        assertEquals(0, opts.getInt("use_ferry"))
        assertEquals(1, opts.getInt("avoid_bad_surfaces"))
        assertFalse(opts.has("use_highways"))
        assertFalse(opts.has("use_tolls"))
    }

    @Test fun costingOptions_returnsNull_whenNoKnobAppliesForMode() {
        // Bicycle has no highway/toll knob, so a highways-only avoidance yields
        // no costing options (the request still routes, just without effect).
        assertNull(RoutingService.valhallaCostingOptions("bicycle", RouteAvoidances(highways = true)))
        assertNull(RoutingService.valhallaCostingOptions("pedestrian", RouteAvoidances(tolls = true)))
        assertNull(RoutingService.valhallaCostingOptions("auto", RouteAvoidances.NONE))
    }

    @Test fun maneuverMapping_coversCommonTypes() {
        assertEquals(TurnType.DEPART, RoutingService.mapValhallaManeuver(1))
        assertEquals(TurnType.ARRIVE, RoutingService.mapValhallaManeuver(4))
        assertEquals(TurnType.SLIGHT_RIGHT, RoutingService.mapValhallaManeuver(9))
        assertEquals(TurnType.RIGHT, RoutingService.mapValhallaManeuver(10))
        assertEquals(TurnType.SHARP_RIGHT, RoutingService.mapValhallaManeuver(11))
        assertEquals(TurnType.UTURN, RoutingService.mapValhallaManeuver(12))
        assertEquals(TurnType.SHARP_LEFT, RoutingService.mapValhallaManeuver(14))
        assertEquals(TurnType.LEFT, RoutingService.mapValhallaManeuver(15))
        assertEquals(TurnType.SLIGHT_LEFT, RoutingService.mapValhallaManeuver(16))
        assertEquals(TurnType.ROUNDABOUT, RoutingService.mapValhallaManeuver(26))
        assertEquals(TurnType.CONTINUE, RoutingService.mapValhallaManeuver(8))
        assertEquals(TurnType.CONTINUE, RoutingService.mapValhallaManeuver(0))
    }
}
