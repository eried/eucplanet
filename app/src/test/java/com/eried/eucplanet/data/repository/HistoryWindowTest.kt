package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HistoryWindowTest {

    private fun samples(vararg t: Long) = t.map { MetricSample(it, it.toFloat()) }.toMutableList()

    @Test
    fun `old samples are dropped from the front and the rest is untouched`() {
        val list = samples(10, 20, 30, 40, 50)
        HistoryWindow.dropOlderThan(list, 30)
        assertEquals(listOf(30L, 40L, 50L), list.map { it.timestampMs })
    }

    @Test
    fun `nothing old, nothing dropped, and an empty buffer is fine`() {
        val list = samples(10, 20, 30)
        HistoryWindow.dropOlderThan(list, 5)
        assertEquals(3, list.size)
        HistoryWindow.dropOlderThan(samples(), 5)
        HistoryWindow.dropOlderThan(list, 999)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `the shown part starts at the first sample inside the window`() {
        val list = samples(10, 20, 30, 40, 50)
        assertEquals(listOf(30L, 40L, 50L), HistoryWindow.shownFrom(list, 25).map { it.timestampMs })
        assertEquals(listOf(30L, 40L, 50L), HistoryWindow.shownFrom(list, 30).map { it.timestampMs })
        assertTrue(HistoryWindow.shownFrom(list, 51).isEmpty())
        assertEquals(5, HistoryWindow.shownFrom(list, 0).size)
    }

    @Test
    fun `what is handed out is a copy the buffer cannot change later`() {
        val list = samples(10, 20, 30)
        val shown = HistoryWindow.shownFrom(list, 0)
        assertNotSame(list, shown)
        list.add(MetricSample(40, 40f))
        list.removeAt(0)
        assertEquals(listOf(10L, 20L, 30L), shown.map { it.timestampMs })
    }

    @Test
    fun `a buffer the clock stepped back in gives what the old pass gave`() {
        val list = samples(100, 110, 120, 60, 70, 130)
        assertEquals(
            list.filter { it.timestampMs >= 105 },
            HistoryWindow.shownFrom(list, 105),
        )
        val expected = list.filter { it.timestampMs >= 105 }
        HistoryWindow.dropOlderThan(list, 105)
        assertEquals(expected, list)
    }

    @Test
    fun `same result as testing every sample, on many random buffers`() {
        val rnd = Random(7)
        repeat(500) {
            var t = 1_000L
            val list = MutableList(rnd.nextInt(0, 60)) {
                // Mostly forward, with equal stamps and the odd step back.
                t += when (rnd.nextInt(10)) { 0 -> -rnd.nextLong(1, 400); 1 -> 0L; else -> rnd.nextLong(1, 300) }
                MetricSample(t, it.toFloat())
            }
            val cut = 1_000L + rnd.nextLong(-200, 9_000)
            assertEquals(list.filter { it.timestampMs >= cut }, HistoryWindow.shownFrom(list, cut))
            val old = list.toMutableList().also { l -> l.removeAll { s -> s.timestampMs < cut } }
            HistoryWindow.dropOlderThan(list, cut)
            assertEquals(old, list)
        }
    }
}
