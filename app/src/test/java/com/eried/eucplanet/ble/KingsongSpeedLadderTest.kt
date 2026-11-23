package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KingSong writes its three alarms and the tiltback in one frame. The app
 * has one alarm slider, so the other two alarms are spread between it and
 * the tiltback.
 */
class KingsongSpeedLadderTest {

    /** Alarm 1, alarm 2, alarm 3 and tiltback, as the frame carries them. */
    private fun ladder(tiltbackKmh: Float, alarmKmh: Float): List<Int> =
        KingsongAdapter().setMaxSpeed(tiltbackKmh, alarmKmh)
            .let { f -> listOf(f[2], f[4], f[6], f[8]).map { it.toInt() and 0xFF } }

    @Test
    fun `the alarms climb from the alarm speed to the tiltback`() {
        assertEquals(listOf(40, 43, 46, 50), ladder(tiltbackKmh = 50f, alarmKmh = 40f))
        assertEquals(listOf(50, 50, 50, 50), ladder(tiltbackKmh = 50f, alarmKmh = 50f))
    }

    @Test
    fun `an alarm above the tiltback is held at the tiltback`() {
        // The two come from separate settings, and a restored or synced file
        // can carry them the wrong way round. Building the frame used to
        // throw, which closed the app.
        assertEquals(listOf(30, 30, 30, 30), ladder(tiltbackKmh = 30f, alarmKmh = 40f))
    }
}
