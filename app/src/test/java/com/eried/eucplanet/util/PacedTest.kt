package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacedTest {

    @Test
    fun `an aged value is read again only once it is old`() {
        var reads = 0
        val v = AgedValue(30_000L) { ++reads }
        assertEquals(1, v.get(1_000L))
        assertEquals(1, v.get(15_000L))
        assertEquals(1, v.get(30_999L))
        assertEquals(2, v.get(31_000L))
        assertEquals(2, reads)
    }

    @Test
    fun `an aged value is read again when the clock went back`() {
        var reads = 0
        val v = AgedValue(30_000L) { ++reads }
        v.get(500_000L)
        assertEquals(2, v.get(1_000L))
    }

    @Test
    fun `a value by instance is made once per object, not per call`() {
        var made = 0
        val v = ByInstance<List<Int>, Int> { made++; it.sum() }
        val a = listOf(1, 2, 3)
        assertEquals(6, v.get(a))
        assertEquals(6, v.get(a))
        assertEquals(1, made)
        // Equal content in another object counts as a change: cheap to be
        // wrong in this direction, and it never serves a stale value.
        assertEquals(6, v.get(listOf(1, 2, 3)))
        assertEquals(2, made)
    }

    @Test
    fun `every lets one through per period`() {
        val e = EveryMs(250L)
        assertTrue(e.due(1_000L))
        assertFalse(e.due(1_100L))
        assertFalse(e.due(1_249L))
        assertTrue(e.due(1_250L))
        assertTrue("a clock that went back must not block", e.due(10L))
    }
}
