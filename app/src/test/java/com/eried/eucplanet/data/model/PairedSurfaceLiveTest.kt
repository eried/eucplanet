package com.eried.eucplanet.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedSurfaceLiveTest {

    private val now = 1_000_000L

    private fun garminLive(silentMs: Long) =
        PairedSurface.isLive(now - silentMs, now, PairedSurface.GARMIN_LIVE_WINDOW_MS)

    @Test
    fun `never heard from the watch is not live`() {
        assertFalse(PairedSurface.isLive(0L, now, PairedSurface.GARMIN_LIVE_WINDOW_MS))
        assertFalse(PairedSurface.isLive(0L, now, PairedSurface.AMAZFIT_LIVE_WINDOW_MS))
    }

    @Test
    fun `garmin stays live across a slow transmit between two beats`() {
        // A 3 s transmit on a congested link, then the next 1.5 s beat.
        assertTrue(garminLive(silentMs = 3_000L + 1_500L))
    }

    @Test
    fun `garmin stays live across an abandoned transmit`() {
        // Bridge.mc TX_ABANDON_MS (8 s) plus the 1.5 s beat that follows it.
        assertTrue(garminLive(silentMs = 8_000L + 1_500L))
    }

    @Test
    fun `garmin goes idle once the watch is silent past the window`() {
        assertFalse(garminLive(silentMs = PairedSurface.GARMIN_LIVE_WINDOW_MS))
        assertFalse(garminLive(silentMs = 30_000L))
    }

    @Test
    fun `amazfit window covers its slowest poll with margin`() {
        // The slowest tier polls once a second (amazfitPollIntervalMsFor).
        assertTrue(PairedSurface.isLive(now - 2_000L, now, PairedSurface.AMAZFIT_LIVE_WINDOW_MS))
        assertFalse(PairedSurface.isLive(now - 3_000L, now, PairedSurface.AMAZFIT_LIVE_WINDOW_MS))
    }
}
