package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuPublishGateTest {

    /** A phone with a published reading, fed new ones 120 ms apart. */
    private class Phone(val gate: ImuPublishGate = ImuPublishGate()) {
        var shownG = 0f; var shownX = 0f; var shownY = 0f
        var now = 100_000L
        var published = 0
        fun read(g: Float, x: Float = 0f, y: Float = 0f): Boolean {
            now += 120
            val pub = gate.shouldPublish(now, shownG, shownX, shownY, g, x, y)
            if (pub) { shownG = g; shownX = x; shownY = y; published++ }
            return pub
        }
    }

    @Test
    fun `a phone lying still publishes about once a second, not eight times`() {
        val p = Phone()
        p.read(0.003f)                       // the first reading always goes out
        val before = p.published
        repeat(83) { p.read(0.003f + (it % 2) * 0.002f) }   // ten seconds of sensor noise
        val perSecond = (p.published - before) / 10.0
        assertTrue("published $perSecond a second", perSecond in 0.9..1.2)
    }

    @Test
    fun `a phone that starts to move is published on that very reading`() {
        val p = Phone()
        p.read(0.002f)
        assertFalse(p.read(0.004f))
        assertTrue("a real movement must go out at once", p.read(0.15f))
        assertEquals(0.15f, p.shownG, 0f)
    }

    @Test
    fun `it resumes after any length of stillness`() {
        // The owner's worry: after hours on the charger, does it come back?
        val p = Phone()
        p.read(0.002f)
        repeat(30_000) { p.read(0.002f) }    // an hour of nothing
        assertTrue(p.read(0.08f))
        assertTrue(p.read(0.30f))
        assertTrue(p.read(0.05f))
    }

    @Test
    fun `a slow drift is published once it adds up to a hundredth of a g`() {
        val gate = ImuPublishGate(keepAliveMs = Long.MAX_VALUE / 4)
        val p = Phone(gate)
        p.read(0f)
        var g = 0f
        var sawPublish = false
        repeat(20) {
            g += 0.001f
            if (p.read(g)) sawPublish = true
        }
        assertTrue(sawPublish)
        assertTrue("the shown value followed the drift", p.shownG >= 0.01f)
    }

    @Test
    fun `sideways movement counts even when the total does not change`() {
        val p = Phone()
        p.read(0.10f, x = 0.10f, y = 0f)
        assertTrue(p.read(0.10f, x = 0f, y = 0.10f))
    }

    @Test
    fun `while riding every reading goes out, as before`() {
        val p = Phone()
        p.read(0f)
        val before = p.published
        repeat(50) { p.read(0.05f + (it % 5) * 0.03f) }
        assertEquals(50, p.published - before)
    }

    @Test
    fun `a clock that steps back does not hold the readings`() {
        val gate = ImuPublishGate()
        assertTrue(gate.shouldPublish(500_000L, 0f, 0f, 0f, 0f, 0f, 0f))
        assertTrue(gate.shouldPublish(1_000L, 0f, 0f, 0f, 0f, 0f, 0f))
    }
}
