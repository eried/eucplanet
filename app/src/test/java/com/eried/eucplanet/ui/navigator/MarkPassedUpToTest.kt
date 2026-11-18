package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * In Full path the engine never raises arrived for a stop in the middle of
 * the tour, it only moves its next stop on. The builder ticks off every stop
 * before that next stop, matched by position so it survives a re-route.
 */
class MarkPassedUpToTest {

    private fun wp(i: Int, passed: Boolean = false) =
        Waypoint(59.0 + i * 0.01, 10.0 + i * 0.01, "s$i", passed = passed)

    private fun at(i: Int) = GeoPoint(59.0 + i * 0.01, 10.0 + i * 0.01)

    private fun flags(list: List<Waypoint>?) = list?.map { it.passed }

    @Test fun `next stop is the first one, nothing is marked`() {
        val list = listOf(wp(0), wp(1), wp(2))
        assertNull(markPassedUpTo(list, at(0)))
    }

    @Test fun `next stop at k marks the stops before it`() {
        val list = listOf(wp(0), wp(1), wp(2), wp(3))
        assertEquals(listOf(true, true, false, false), flags(markPassedUpTo(list, at(2))))
    }

    @Test fun `no match marks nothing`() {
        val list = listOf(wp(0), wp(1))
        assertNull(markPassedUpTo(list, at(7)))
        assertNull(markPassedUpTo(list, null))
        assertNull(markPassedUpTo(emptyList(), at(0)))
    }

    @Test fun `already reached stops are left as they are`() {
        val list = listOf(wp(0, true), wp(1), wp(2))
        val out = markPassedUpTo(list, at(2))!!
        assertEquals(listOf(true, true, false), flags(out))
        assertEquals(list[0], out[0])
        // Next stop right after the reached block: nothing to do.
        assertNull(markPassedUpTo(list, at(1)))
    }

    @Test fun `a reached stop at the same spot is skipped when matching`() {
        // A loop: the last stop sits on the first. The first is already
        // reached, so the next stop is the last one.
        val loop = listOf(wp(0, true), wp(1), wp(0))
        assertEquals(listOf(true, true, false), flags(markPassedUpTo(loop, at(0))))
    }

    @Test fun `inclusive also marks the matched stop`() {
        val list = listOf(wp(0), wp(1), wp(2))
        assertEquals(listOf(true, false, false), flags(markPassedUpTo(list, at(0), inclusive = true)))
        assertEquals(listOf(true, true, true), flags(markPassedUpTo(list, at(2), inclusive = true)))
        assertNull(markPassedUpTo(listOf(wp(0, true)), at(0), inclusive = true))
    }

    @Test fun `position match tolerates rounding below a micro degree`() {
        val list = listOf(wp(0), wp(1))
        val near = GeoPoint(at(1).lat + 5e-7, at(1).lng - 5e-7)
        assertEquals(listOf(true, false), flags(markPassedUpTo(list, near)))
    }
}
