package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HUD map keeps facing the way the rider was going when they stop. */
class HeldHeadingTest {

    @Test fun `nothing to show before the first heading`() {
        assertTrue(HeldHeading().update(null, null).isNaN())
    }

    @Test fun `a stop keeps the heading the rider arrived with`() {
        val h = HeldHeading()
        assertEquals(90f, h.update(90f, 5f), 0f)
        // Stopped: the fix has no bearing at all.
        assertEquals(90f, h.update(null, 0f), 0f)
        // Stopped, and the phone reports a jittery one anyway.
        assertEquals(90f, h.update(271f, 0.2f), 0f)
        // Off again.
        assertEquals(180f, h.update(180f, 3f), 0f)
    }

    @Test fun `the first heading counts even at a crawl`() {
        assertEquals(45f, HeldHeading().update(45f, 0.1f), 0f)
    }

    @Test fun `a fix without a speed still turns the map`() {
        val h = HeldHeading()
        h.update(10f, 5f)
        assertEquals(20f, h.update(20f, null), 0f)
    }
}
