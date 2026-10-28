package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which backend a request goes to, the walking profile Valhalla gets, and the
 * rider's heading on a reroute. Pure logic, no network.
 */
class RoutingBackendTest {

    private val def = RoutingService.DEFAULT_ROUTER

    @Test fun cycling_goesToValhalla_onTheDefaultRouter() {
        // OSRM's bike profile scores a main road like a cycleway, so the
        // rider was sent along the straight main road.
        assertTrue(RoutingService.prefersValhalla(TravelMode.CYCLING, def, RouteAvoidances.NONE))
        assertTrue(RoutingService.prefersValhalla(TravelMode.CYCLING, "$def/", RouteAvoidances.NONE))
    }

    @Test fun walkingAndDriving_stayOnOsrm_withoutAvoidances() {
        assertFalse(RoutingService.prefersValhalla(TravelMode.WALKING, def, RouteAvoidances.NONE))
        assertFalse(RoutingService.prefersValhalla(TravelMode.DRIVING, def, RouteAvoidances.NONE))
    }

    @Test fun anAvoidance_goesToValhalla_inEveryMode() {
        val avoid = RouteAvoidances(ferries = true)
        assertTrue(RoutingService.prefersValhalla(TravelMode.WALKING, def, avoid))
        assertTrue(RoutingService.prefersValhalla(TravelMode.DRIVING, def, avoid))
    }

    @Test fun ownRouter_keepsCycling_onIt() {
        assertFalse(RoutingService.prefersValhalla(TravelMode.CYCLING, "https://osrm.example.org", RouteAvoidances.NONE))
    }

    @Test fun walking_onValhalla_prefersFootways() {
        val opts = RoutingService.valhallaCostingOptions("pedestrian", RouteAvoidances.NONE)!!
            .getJSONObject("pedestrian")
        assertTrue(opts.getDouble("walkway_factor") < 1.0)
        assertTrue(opts.getDouble("sidewalk_factor") < 1.0)
        assertTrue(opts.getDouble("alley_factor") > 1.0)
    }

    @Test fun walking_keepsItsProfile_withAnAvoidance() {
        val opts = RoutingService.valhallaCostingOptions("pedestrian", RouteAvoidances(ferries = true))!!
            .getJSONObject("pedestrian")
        assertEquals(0, opts.getInt("use_ferry"))
        assertTrue(opts.has("walkway_factor"))
    }

    @Test fun valhalla_headingGoesOnTheFirstLocationOnly() {
        val locs = RoutingService.valhallaLocations(listOf(Waypoint(1.0, 2.0), Waypoint(3.0, 4.0)), 359.6)
        assertEquals(0, locs.getJSONObject(0).getInt("heading"))
        assertEquals(RoutingService.HEADING_TOLERANCE_DEG, locs.getJSONObject(0).getInt("heading_tolerance"))
        assertFalse(locs.getJSONObject(1).has("heading"))
        assertEquals(2.0, locs.getJSONObject(0).getDouble("lon"), 0.0)
    }

    @Test fun valhalla_noHeading_noField() {
        val locs = RoutingService.valhallaLocations(listOf(Waypoint(1.0, 2.0), Waypoint(3.0, 4.0)), null)
        assertFalse(locs.getJSONObject(0).has("heading"))
    }

    @Test fun osrm_bearingOnTheFirstCoordinate_othersEmpty() {
        assertEquals("90,${RoutingService.HEADING_TOLERANCE_DEG};;", RoutingService.osrmBearings(3, 90.2))
        assertEquals("0,${RoutingService.HEADING_TOLERANCE_DEG};", RoutingService.osrmBearings(2, -0.3))
        assertNull(RoutingService.osrmBearings(2, null))
    }
}
