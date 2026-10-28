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
}
