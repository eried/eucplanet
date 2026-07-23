package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStartGuardTest {

    private val rider = GeoPoint(59.9139, 10.7522) // Oslo-ish

    @Test
    fun `near stop is within start distance`() {
        val near = GeoPoint(59.9139, 10.7700) // ~1 km east
        assertTrue(NavStartGuard.originWithinStart(rider, near, maxKm = 50))
    }

    @Test
    fun `far stop is beyond start distance`() {
        val far = GeoPoint(60.4500, 10.7522) // ~60 km north
        assertFalse(NavStartGuard.originWithinStart(rider, far, maxKm = 50))
    }

    @Test
    fun `stop just inside the threshold is within start distance`() {
        val maxKm = 30
        val stop = northOf(rider, meters = maxKm * 1000.0 - MARGIN_M)

        assertTrue(NavStartGuard.originWithinStart(rider, stop, maxKm))
    }

    @Test
    fun `stop just outside the threshold is beyond start distance`() {
        val maxKm = 30
        val stop = northOf(rider, meters = maxKm * 1000.0 + MARGIN_M)

        assertFalse(NavStartGuard.originWithinStart(rider, stop, maxKm))
    }

    /**
     * Builds a point due north of [base] at exactly [meters], so the
     * haversine distance between them is predictable: along a meridian the
     * great-circle distance reduces to `EARTH_RADIUS_M * dLatRadians`, with
     * no longitude term to introduce error. Verified below against
     * [GeoMath.distanceM] rather than trusted blindly.
     */
    private fun northOf(base: GeoPoint, meters: Double): GeoPoint {
        val degOffset = Math.toDegrees(meters / EARTH_RADIUS_M)
        return GeoPoint(base.lat + degOffset, base.lng)
    }

    @Test
    fun `northOf test fixture places the stop at the intended distance`() {
        val maxKm = 30
        val inside = northOf(rider, meters = maxKm * 1000.0 - MARGIN_M)
        val outside = northOf(rider, meters = maxKm * 1000.0 + MARGIN_M)

        assertEquals(maxKm * 1000.0 - MARGIN_M, GeoMath.distanceM(rider, inside), 0.5)
        assertEquals(maxKm * 1000.0 + MARGIN_M, GeoMath.distanceM(rider, outside), 0.5)
    }

    private companion object {
        /** Meters inside/outside the threshold the straddling tests use. */
        const val MARGIN_M = 300.0

        /** Matches the private constant in [GeoMath]. */
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
