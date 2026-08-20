package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings

/**
 * When a ride starts and stops recording itself.
 *
 * This is the quietest thing in the app to get wrong: nobody notices a missing
 * auto-start until they finish a ride and find no trip, and by then the ride is
 * gone. The conditions live here, away from the service that owns the BLE
 * connection and the coroutine loops, so they can be stated as tests.
 *
 * Two rules, deliberately not symmetrical:
 *
 *  - starting needs the wheel connected and actually moving. Recording from a
 *    wheel sitting in a hallway produces trips nobody made.
 *  - stopping does not need the wheel connected. The normal way a ride ends is
 *    the rider switching the wheel off, so treating a disconnect as "not
 *    moving" is what lets the trip close instead of running forever.
 */
object AutoRecordPolicy {

    /** Below this the wheel is standing still, in km/h. Deliberately tiny: a
     *  wheel reports small non-zero speeds while balancing under a rider, and
     *  the idle timer is what decides a ride has ended, not this. */
    const val MOTION_MIN_KMH = 0.1f

    fun isMoving(speedKmh: Float): Boolean = kotlin.math.abs(speedKmh) > MOTION_MIN_KMH

    /**
     * Whether telemetry just now should begin a recording.
     *
     * @param moving from [isMoving] on the wheel's speed
     * @param connected the wheel is connected right now
     * @param alreadyRecording a trip is open, so there is nothing to start
     */
    fun shouldStart(
        settings: AppSettings,
        moving: Boolean,
        connected: Boolean,
        alreadyRecording: Boolean,
    ): Boolean = settings.autoRecord &&
        settings.autoRecordStartInMotion &&
        moving &&
        connected &&
        !alreadyRecording

    /**
     * Whether an open recording has been idle long enough to close.
     *
     * @param idleMs how long since the wheel was last seen moving
     */
    fun shouldStop(
        settings: AppSettings,
        recording: Boolean,
        idleMs: Long,
    ): Boolean = settings.autoRecord &&
        settings.autoRecordStartInMotion &&
        recording &&
        settings.autoRecordStopIdleSeconds > 0 &&
        idleMs >= settings.autoRecordStopIdleSeconds * 1000L
}
