package com.eried.eucplanet.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ride's silent track rests after the quiet window; a preview holds it regardless. */
class RouteKeepAliveTest {

    private val quiet = 120_000L
    private val last = 1_000_000L

    @Test fun `a preview keeps the route warm long after the last sound`() {
        assertTrue(routeKeepAliveWanted(preview = true, ride = false, nowMs = last + 10 * quiet, lastSoundMs = last, quietMs = quiet))
    }

    @Test fun `a ride keeps the route warm inside the quiet window`() {
        assertTrue(routeKeepAliveWanted(preview = false, ride = true, nowMs = last, lastSoundMs = last, quietMs = quiet))
        assertTrue(routeKeepAliveWanted(preview = false, ride = true, nowMs = last + quiet - 1, lastSoundMs = last, quietMs = quiet))
    }

    @Test fun `a ride lets the route rest once the window has passed`() {
        assertFalse(routeKeepAliveWanted(preview = false, ride = true, nowMs = last + quiet, lastSoundMs = last, quietMs = quiet))
    }

    @Test fun `nobody asking means no route`() {
        assertFalse(routeKeepAliveWanted(preview = false, ride = false, nowMs = last, lastSoundMs = last, quietMs = quiet))
    }
}
