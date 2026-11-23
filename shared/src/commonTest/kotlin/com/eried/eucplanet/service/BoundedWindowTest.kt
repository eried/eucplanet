package com.eried.eucplanet.service

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedWindowTest {

    private fun ArrayDeque<Long>.push(t: Long, maxAge: Long = 1_000, maxCount: Int = 100) =
        addToWindow(t, t, { it }, maxAge, maxCount)

    @Test
    fun dropsSamplesOlderThanTheWindow() {
        val d = ArrayDeque<Long>()
        listOf(0L, 500L, 1_000L, 1_600L).forEach { d.push(it) }
        assertEquals(listOf(1_000L, 1_600L), d.toList())
    }

    @Test
    fun clockGoingBackwardsStartsOverInsteadOfGrowingForever() {
        val d = ArrayDeque<Long>()
        d.push(10_000L)
        // The clock steps back an hour: every later sample is "older" than the
        // first one, so the age check alone would never trim again.
        for (t in 0L until 5_000L step 10) d.push(t)
        assertEquals(true, d.size <= 101)
        assertEquals(4_990L, d.last())
    }

    @Test
    fun countCapHoldsEvenWithIdenticalTimestamps() {
        val d = ArrayDeque<Long>()
        repeat(10_000) { d.push(42L, maxCount = 50) }
        assertEquals(50, d.size)
    }
}
