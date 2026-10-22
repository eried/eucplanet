package com.eried.eucplanet.ble.virtual

import org.junit.Assert.assertEquals
import org.junit.Test

class SimTripMeterTest {

    @Test
    fun `distance is speed integrated over the ticks`() {
        val trip = SimTripMeter()
        assertEquals(0, trip.advance(0L, 36f))
        // 36 km/h is 10 m/s: ten seconds in 100 ms ticks is 100 m.
        var meters = 0
        for (t in 100L..10_000L step 100L) meters = trip.advance(t, 36f)
        assertEquals(100, meters)
    }

    @Test
    fun `reverse speed still counts forward and reset starts over`() {
        val trip = SimTripMeter()
        trip.advance(0L, -36f)
        assertEquals(10, trip.advance(1_000L, -36f))
        trip.reset()
        assertEquals(0, trip.advance(5_000L, 36f))
    }
}
