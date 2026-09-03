package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which sensor speaks for the tyre when more than one can.
 *
 * A rider can have the wheel's own relayed pressure and a cap they screwed on
 * themselves, and with several wheels, several caps. Only one of them is the
 * answer at any moment, and picking it by "whoever spoke last" would hand the
 * badge back and forth on nothing but timing: these caps report when the
 * pressure moves, so a healthy one is silent on a settled tyre.
 */
class TpmsActiveSensorTest {

    private val t0 = 1_000_000L
    private fun cap(addr: String, at: Long) = TpmsPolicy.Candidate(addr, TpmsSource.PAIRED, at)
    private fun wheel(at: Long) = TpmsPolicy.Candidate(null, TpmsSource.WHEEL, at)

    @Test fun `with only the wheel, the wheel is active`() {
        val a = TpmsPolicy.pickActive(listOf(wheel(t0)), t0, null)
        assertEquals(TpmsSource.WHEEL, a?.source)
    }

    @Test fun `a cap takes over from the wheel the moment it speaks`() {
        // No hold in this direction: fitting a cap IS the decision to use it.
        val a = TpmsPolicy.pickActive(listOf(wheel(t0), cap("A", t0)), t0, null)
        assertEquals("A", a?.address)
    }

    @Test fun `the wheel does not steal it back while the cap is merely pausing`() {
        // The cap reports on change, so a quiet minute is normal. Handing the
        // badge back to the wheel here would flicker between two tyres.
        val now = t0 + 5 * 60 * 1000L
        val a = TpmsPolicy.pickActive(listOf(wheel(now), cap("A", t0)), now, "A")
        assertEquals("A", a?.address)
    }

    @Test fun `the wheel comes back once every cap has properly gone quiet`() {
        val now = t0 + TpmsPolicy.STALE_AFTER_MS
        val a = TpmsPolicy.pickActive(listOf(wheel(now), cap("A", t0)), now, "A")
        assertEquals(TpmsSource.WHEEL, a?.source)
    }

    @Test fun `the active cap keeps the badge while it is still talking`() {
        // B spoke more recently, but A is active and has not fallen silent.
        // Switching on freshness alone is the flapping this prevents.
        val now = t0 + 10_000
        val a = TpmsPolicy.pickActive(listOf(cap("A", now - 5_000), cap("B", now)), now, "A")
        assertEquals("A", a?.address)
    }

    @Test fun `a challenger takes over once the active one falls silent`() {
        val now = t0 + TpmsPolicy.ACTIVE_HOLD_MS + 5_000
        val a = TpmsPolicy.pickActive(listOf(cap("A", t0), cap("B", now - 1_000)), now, "A")
        assertEquals("B", a?.address)
    }

    @Test fun `the freshest challenger wins, not just any of them`() {
        val now = t0 + TpmsPolicy.ACTIVE_HOLD_MS + 5_000
        val a = TpmsPolicy.pickActive(
            listOf(cap("A", t0), cap("B", now - 30_000), cap("C", now - 1_000)), now, "A"
        )
        assertEquals("C", a?.address)
    }

    @Test fun `nothing is active when everything has gone stale`() {
        val now = t0 + TpmsPolicy.STALE_AFTER_MS
        assertNull(TpmsPolicy.pickActive(listOf(cap("A", t0)), now, "A"))
        assertNull(TpmsPolicy.pickActive(emptyList(), now, null))
    }

    @Test fun `a cap that goes quiet and comes back keeps its place`() {
        // A falls silent, B takes over, then A speaks again. B is the
        // incumbent now and keeps it while it is talking: the badge follows
        // the rule, not whoever shouted most recently.
        val t1 = t0 + TpmsPolicy.ACTIVE_HOLD_MS + 1_000
        val afterSwitch = TpmsPolicy.pickActive(listOf(cap("A", t0), cap("B", t1)), t1, "A")
        assertEquals("B", afterSwitch?.address)
        val t2 = t1 + 5_000
        val stillB = TpmsPolicy.pickActive(listOf(cap("A", t2), cap("B", t1)), t2, "B")
        assertEquals("B", stillB?.address)
    }
}
