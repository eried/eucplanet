package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a decoded telemetry frame must not throw away.
 *
 * A frame is built from what the parser returned, so every field the parser does
 * not fill arrives at its default. For wheel fields that is the honest answer.
 * For the handful the phone works out for itself, on loops that run at their own
 * cadence, it is destructive: the loop is the only copy.
 *
 * On an InMotion V1 the poll is 250 ms and the ride-efficiency loop runs at 1 Hz,
 * so before this the tiles were overwritten with NaN three frames out of four.
 * The rider saw CONSUMPTION and RANGE answer for an instant a few times a ride
 * and read blank the rest of the time.
 */
class WheelDataCarryOverTest {

    /** The dashboard's state after a while riding. */
    private val previous = WheelData(
        speed = 25f,
        gForce = 1.2f,
        accelX = 0.3f,
        accelY = 0.9f,
        forwardGFromSpeed = 0.15f,
        whPerKmRecent = 18.4f,
        rangeKmEstimate = 34f,
    )

    /** The next frame off the wire: a wheel's own fields, nothing else. */
    private val decoded = WheelData(speed = 26f, voltage = 78f, current = 4.2f)

    @Test
    fun `a frame keeps the numbers the phone worked out`() {
        val merged = decoded.carryPhoneSideFrom(previous)
        assertEquals(18.4f, merged.whPerKmRecent, 0.001f)
        assertEquals(34f, merged.rangeKmEstimate, 0.001f)
        assertEquals(1.2f, merged.gForce, 0.001f)
        assertEquals(0.3f, merged.accelX, 0.001f)
        assertEquals(0.9f, merged.accelY, 0.001f)
        assertEquals(0.15f, merged.forwardGFromSpeed, 0.001f)
    }

    @Test
    fun `the wheel's own fields still come from the frame`() {
        val merged = decoded.carryPhoneSideFrom(previous)
        assertEquals(26f, merged.speed, 0.001f)
        assertEquals(78f, merged.voltage, 0.001f)
        assertEquals(4.2f, merged.current, 0.001f)
    }

    @Test
    fun `a blank previous carries nothing across`() {
        // First frame of a session: there is nothing to keep, and the defaults
        // have to survive rather than turn into zeroes that read as real.
        val merged = decoded.carryPhoneSideFrom(WheelData())
        assertTrue(merged.whPerKmRecent.isNaN())
        assertTrue(merged.rangeKmEstimate.isNaN())
        assertEquals(0f, merged.gForce, 0.001f)
    }
}
