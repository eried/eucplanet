package com.eried.eucplanet.hud.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed dial's needle has a length.
 *
 * A line whose two ends are the same point draws nothing, and nothing looks
 * like a dial that was never meant to have a needle.
 */
class SpeedNeedleTest {

    @Test fun `the needle crosses the ring from edge to edge`() {
        val (inner, outer) = speedNeedleRadii(diameter = 400f, stroke = 20f)
        assertEquals(190f, inner, 0.001f)
        assertEquals(210f, outer, 0.001f)
    }

    @Test fun `it is as long as the ring is wide, at any size`() {
        for (stroke in listOf(6f, 12f, 48f)) {
            val (inner, outer) = speedNeedleRadii(diameter = 300f, stroke = stroke)
            assertTrue(outer > inner)
            assertEquals(stroke, outer - inner, 0.001f)
        }
    }
}
