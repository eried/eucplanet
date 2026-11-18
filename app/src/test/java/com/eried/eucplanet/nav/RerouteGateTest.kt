package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteGateTest {

    @Test
    fun `a fresh gate lets the first reroute start and speaks it`() {
        val g = RerouteGate()
        assertTrue(g.mayTry(0L))
        assertTrue(g.speak())
    }

    @Test
    fun `a failed reroute waits the reroute delay before the next try`() {
        val g = RerouteGate()
        g.failed(now = 10_000L, delayMs = 8_000L)
        assertFalse(g.mayTry(10_001L))
        assertFalse(g.mayTry(17_999L))
        assertTrue(g.mayTry(18_000L))
    }

    @Test
    fun `each failure in a row doubles the wait, up to the cap`() {
        val g = RerouteGate()
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(6) {
            g.failed(now, 8_000L)
            var t = now
            while (!g.mayTry(t)) t += 1_000L
            waits += t - now
            now = t
        }
        assertEquals(listOf(8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `only the first try of a failing run is spoken`() {
        val g = RerouteGate()
        assertTrue(g.speak())
        g.failed(0L, 8_000L)
        assertFalse(g.speak())
        g.failed(8_000L, 8_000L)
        assertFalse(g.speak())
    }

    @Test
    fun `reset after a success or back on route starts over`() {
        val g = RerouteGate()
        g.failed(0L, 8_000L)
        g.failed(8_000L, 8_000L)
        g.reset()
        assertTrue(g.mayTry(8_001L))
        assertTrue(g.speak())
        g.failed(10_000L, 8_000L)
        assertTrue(g.mayTry(18_000L))
    }
}
