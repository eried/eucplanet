package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a ride records itself.
 *
 * The failure here is silent and unrecoverable: a rider finishes, opens the
 * app, and the ride was never recorded. There is nothing to retry. So the
 * conditions are pinned rather than trusted, including the asymmetry - a
 * recording will not start unless the wheel is connected and moving, but it
 * will stop without either, because switching the wheel off is how rides end.
 */
class AutoRecordPolicyTest {

    private val on = AppSettings(
        autoRecord = true,
        autoRecordStartInMotion = true,
        autoRecordStopIdleSeconds = 180,
    )

    // --- motion ------------------------------------------------------------

    @Test fun `a wheel balancing under a rider still counts as moving`() {
        assertTrue(AutoRecordPolicy.isMoving(0.4f))
    }

    @Test fun `a parked wheel is not moving`() {
        assertFalse(AutoRecordPolicy.isMoving(0f))
        assertFalse(AutoRecordPolicy.isMoving(0.05f))
    }

    @Test fun `rolling backwards is moving too`() {
        assertTrue(AutoRecordPolicy.isMoving(-8f))
    }

    // --- starting ----------------------------------------------------------

    @Test fun `moving on a connected wheel starts the recording`() {
        assertTrue(AutoRecordPolicy.shouldStart(on, moving = true, connected = true, alreadyRecording = false))
    }

    @Test fun `a wheel standing in the hallway records nothing`() {
        assertFalse(AutoRecordPolicy.shouldStart(on, moving = false, connected = true, alreadyRecording = false))
    }

    @Test fun `no connection means no recording, whatever the speed says`() {
        assertFalse(AutoRecordPolicy.shouldStart(on, moving = true, connected = false, alreadyRecording = false))
    }

    @Test fun `an open recording is not started twice`() {
        assertFalse(AutoRecordPolicy.shouldStart(on, moving = true, connected = true, alreadyRecording = true))
    }

    @Test fun `auto-record off means the app never starts on its own`() {
        val off = on.copy(autoRecord = false)
        assertFalse(AutoRecordPolicy.shouldStart(off, moving = true, connected = true, alreadyRecording = false))
    }

    @Test fun `with start-in-motion off, motion alone does not start a ride`() {
        // That mode starts on connect instead, which the service handles.
        val onConnect = on.copy(autoRecordStartInMotion = false)
        assertFalse(AutoRecordPolicy.shouldStart(onConnect, moving = true, connected = true, alreadyRecording = false))
    }

    // --- stopping ----------------------------------------------------------

    @Test fun `idle past the threshold closes the trip`() {
        assertTrue(AutoRecordPolicy.shouldStop(on, recording = true, idleMs = 180_000))
        assertTrue(AutoRecordPolicy.shouldStop(on, recording = true, idleMs = 200_000))
    }

    @Test fun `a traffic light does not end the ride`() {
        assertFalse(AutoRecordPolicy.shouldStop(on, recording = true, idleMs = 45_000))
    }

    @Test fun `nothing to stop when nothing is recording`() {
        assertFalse(AutoRecordPolicy.shouldStop(on, recording = false, idleMs = 10 * 60_000))
    }

    @Test fun `a zero threshold never auto-stops`() {
        // Otherwise every sample would close the trip the moment it opened.
        val never = on.copy(autoRecordStopIdleSeconds = 0)
        assertFalse(AutoRecordPolicy.shouldStop(never, recording = true, idleMs = Long.MAX_VALUE))
    }

    @Test fun `the rider's own threshold is what counts`() {
        val short = on.copy(autoRecordStopIdleSeconds = 30)
        assertFalse(AutoRecordPolicy.shouldStop(short, recording = true, idleMs = 29_000))
        assertTrue(AutoRecordPolicy.shouldStop(short, recording = true, idleMs = 30_000))
    }

    @Test fun `stopping needs no connection, because that is how rides end`() {
        // The rider switches the wheel off; there is no telemetry to say
        // "stopped". Idle time alone has to be enough, or the trip never closes.
        assertTrue(AutoRecordPolicy.shouldStop(on, recording = true, idleMs = 10 * 60_000))
    }

    @Test fun `switching auto-record off leaves a running recording alone`() {
        // The rider is in charge of a trip they started; the app stops only what
        // it started for them.
        val off = on.copy(autoRecord = false)
        assertFalse(AutoRecordPolicy.shouldStop(off, recording = true, idleMs = 10 * 60_000))
    }
}
