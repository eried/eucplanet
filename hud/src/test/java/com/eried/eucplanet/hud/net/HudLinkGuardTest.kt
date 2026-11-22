package com.eried.eucplanet.hud.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two phones on one HUD took turns on the screen. Only one links. */
class HudLinkGuardTest {

    @Test fun `a free HUD takes the first phone`() {
        assertTrue(HudLinkGuard.admits(null, Long.MAX_VALUE, "192.168.43.5"))
    }

    @Test fun `a second phone is turned away while the first is talking`() {
        assertFalse(HudLinkGuard.admits("192.168.43.5", 200L, "192.168.43.9"))
        assertFalse(HudLinkGuard.admits("192.168.43.5", HudLinkGuard.TALKING_MS, "192.168.43.9"))
    }

    @Test fun `the same phone dialing again takes over`() {
        assertTrue(HudLinkGuard.admits("192.168.43.5", 200L, "192.168.43.5"))
    }

    @Test fun `a phone that went quiet gives way`() {
        assertTrue(HudLinkGuard.admits("192.168.43.5", HudLinkGuard.TALKING_MS + 1, "192.168.43.9"))
    }
}
