package com.eried.eucplanet.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The step to OFF both phones take: a LOW fix through the grace, then released. */
class GpsIdleOffTest {
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val applied = mutableListOf<GpsTier>()
    private var current: GpsTier? = null
    private val idleOff = GpsIdleOff { ms, block ->
        val t = ms to block
        timers += t
        ({ timers.remove(t) })
    }

    private fun decide(tier: GpsTier, graceSec: Int = 30) =
        idleOff.decide(tier, current, graceSec) { applied += it; current = it }

    private fun fire() {
        val t = timers.removeAt(0)
        t.second()
    }

    @Test fun offHoldsLowThroughTheGraceThenReleases() {
        decide(GpsTier.BALANCED)
        decide(GpsTier.OFF)
        assertEquals(listOf(GpsTier.BALANCED, GpsTier.LOW), applied)
        assertEquals(30_000L, timers.single().first)
        fire()
        assertEquals(GpsTier.OFF, current)
    }

    @Test fun aRepeatedOffDoesNotRescheduleOrThrash() {
        decide(GpsTier.HIGH)
        decide(GpsTier.OFF)
        decide(GpsTier.OFF)
        assertEquals(1, timers.size)
        fire()
        decide(GpsTier.OFF)
        assertEquals(listOf(GpsTier.HIGH, GpsTier.LOW, GpsTier.OFF), applied)
        assertEquals(0, timers.size)
    }

    @Test fun aTierAboveOffCancelsThePendingOff() {
        decide(GpsTier.BALANCED)
        decide(GpsTier.OFF)
        decide(GpsTier.HIGH)
        assertEquals(0, timers.size)
        assertEquals(GpsTier.HIGH, current)
    }

    @Test fun noGraceReleasesAtOnce() {
        decide(GpsTier.BALANCED)
        decide(GpsTier.OFF, graceSec = 0)
        assertEquals(listOf(GpsTier.BALANCED, GpsTier.OFF), applied)
        assertEquals(0, timers.size)
    }
}
