package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.data.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which router a leg tries first. A rider with no tiles never meets offline. */
class RouterPlanTest {

    @Test fun cycling_coveredAndShort_isEligible() =
        assertTrue(RouterPlan.offlineEligible(TravelMode.CYCLING, covered = true, legKm = 30.0))

    @Test fun driving_isNeverEligible() =
        assertFalse(RouterPlan.offlineEligible(TravelMode.DRIVING, covered = true, legKm = 5.0))

    @Test fun straight_isNeverEligible() =
        assertFalse(RouterPlan.offlineEligible(TravelMode.STRAIGHT, covered = true, legKm = 5.0))

    @Test fun overTheLimit_isNotEligible() =
        assertFalse(RouterPlan.offlineEligible(TravelMode.CYCLING, covered = true, legKm = 120.0))

    @Test fun uncovered_isNotEligible() =
        assertFalse(RouterPlan.offlineEligible(TravelMode.WALKING, covered = false, legKm = 5.0))

    @Test fun fallback_triesOnlineFirst() =
        assertEquals(listOf(Backend.ONLINE, Backend.OFFLINE), RouterPlan.order(OfflineRoutingMode.FALLBACK, eligible = true))

    @Test fun prefer_triesOfflineFirst() =
        assertEquals(listOf(Backend.OFFLINE, Backend.ONLINE), RouterPlan.order(OfflineRoutingMode.PREFER, eligible = true))

    @Test fun notEligible_isOnlineOnly() {
        assertEquals(listOf(Backend.ONLINE), RouterPlan.order(OfflineRoutingMode.FALLBACK, eligible = false))
        assertEquals(listOf(Backend.ONLINE), RouterPlan.order(OfflineRoutingMode.PREFER, eligible = false))
    }

    @Test fun aLegCountsOnlyTheSquaresItsEndsAreIn() {
        // A coastal town near a square's edge: the padded box reached into a
        // sea square the server does not have, so it never routed offline.
        val t = RouterPlan.legTiles(
            com.eried.eucplanet.data.model.GeoPoint(59.98, 10.75),
            com.eried.eucplanet.data.model.GeoPoint(59.97, 10.80),
        )
        assertEquals(setOf("E10_N55"), t)
    }

    @Test fun legsJoin_onlyWhenEveryLegRouted() {
        val leg = com.eried.eucplanet.data.model.NavRoute(
            "r", emptyList(), TravelMode.CYCLING, emptyList(), emptyList(), 1.0,
        )
        assertEquals(listOf(leg, leg), RouterPlan.allRouted(listOf(leg, leg)))
        // A leg that no router could route: the whole route has failed, it is
        // not swapped for a straight line behind the rider's back.
        assertEquals(null, RouterPlan.allRouted(listOf(leg, null)))
        assertEquals(null, RouterPlan.allRouted(emptyList<com.eried.eucplanet.data.model.NavRoute?>()))
    }
}
