package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD may ask Android for a WiFi scan while off the air, but only as
 * often as a foreground app is allowed to: four per two minutes. Ask more
 * and the OS refuses silently, which would waste the one request that lands
 * right after the hotspot comes back.
 */
class RescanPacerTest {

    @Test fun `the first request after a drop is granted at once`() {
        val pacer = RescanPacer()
        assertTrue(pacer.claim(nowMs = 1_000L))
    }

    @Test fun `requests inside the window are refused, the next one after it is granted`() {
        val pacer = RescanPacer(minIntervalMs = 35_000L)
        assertTrue(pacer.claim(0L))
        assertFalse("1.5 s later, the watchdog's recovery tick", pacer.claim(1_500L))
        assertFalse("34 s later, still inside", pacer.claim(34_000L))
        assertTrue("35 s later", pacer.claim(35_000L))
        assertFalse("the refused ones did not move the clock", pacer.claim(36_000L))
        assertTrue(pacer.claim(70_000L))
    }

    @Test fun `the default interval stays inside the foreground budget`() {
        // Four scans in any 120 s window. At the default spacing the fifth
        // request lands with only three inside the window behind it.
        val pacer = RescanPacer()
        var t = 0L
        var granted = 0
        while (t <= 120_000L) {
            if (pacer.claim(t)) granted++
            t += 500L
        }
        assertTrue("granted $granted in 120 s, the OS allows 4", granted <= 4)
    }

    @Test fun `back on the air resets the pacer so the next drop scans immediately`() {
        val pacer = RescanPacer()
        assertTrue(pacer.claim(0L))
        pacer.reset()
        assertTrue(pacer.claim(2_000L))
    }
}
