package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
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
    fun `zero distance is within any positive threshold`() {
        assertTrue(NavStartGuard.originWithinStart(rider, rider, maxKm = 1))
    }
}
