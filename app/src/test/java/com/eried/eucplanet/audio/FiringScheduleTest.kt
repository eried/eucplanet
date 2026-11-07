package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cylinders fire at their angles of the cycle, uneven ones included. */
class FiringScheduleTest {

    private fun firings(angles: FloatArray, cycleDeg: Float, stepDeg: Float, totalDeg: Float): List<Float> {
        val s = FiringSchedule(angles, cycleDeg)
        val out = mutableListOf<Float>()
        var deg = 0f
        while (deg < totalDeg) {
            val before = deg
            deg += stepDeg
            repeat(s.advance(stepDeg / cycleDeg)) { out += before }
        }
        return out
    }

    @Test
    fun `a single fires once per cycle`() {
        val f = firings(floatArrayOf(0f), 720f, stepDeg = 10f, totalDeg = 2160f)
        assertEquals(listOf(0f, 720f, 1440f), f)
    }

    @Test
    fun `a 45 degree V-twin fires at 0 and 315 of every 720`() {
        val f = firings(floatArrayOf(0f, 315f), 720f, stepDeg = 5f, totalDeg = 1440f)
        assertEquals(listOf(0f, 315f, 720f, 1035f), f)
    }

    @Test
    fun `a step that crosses two firings reports both`() {
        val s = FiringSchedule(floatArrayOf(0f, 180f), 360f)
        assertEquals(2, s.advance(200f / 360f))
    }

    @Test
    fun `angles come in any order`() {
        val f = firings(floatArrayOf(540f, 0f, 360f, 180f), 720f, stepDeg = 10f, totalDeg = 720f)
        assertEquals(listOf(0f, 180f, 360f, 540f), f)
    }
}
