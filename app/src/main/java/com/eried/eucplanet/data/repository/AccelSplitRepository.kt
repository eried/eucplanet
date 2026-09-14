package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AccelSplitMode
import com.eried.eucplanet.data.model.AccelSplitSettings
import com.eried.eucplanet.service.AccelSplitTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The speed-split session: the tracker and what it has recorded so far.
 *
 * The tracker used to be a private field of the wheel service, which was
 * fine while the only thing that ever looked at it was the voice. Now the
 * settings screen shows the session's times and offers to clear them, and a
 * dashboard button switches the splits off and on mid-ride, so the session
 * has to live somewhere both can reach and outlive whichever screen is open.
 *
 * Switching off PAUSES. It stops the tracking and drops any run in flight, so
 * a gap in the samples can never be read as a very slow step, but the
 * previous-run and session-best times stay. A rider who silences the splits
 * for a stretch of road and turns them back on should still be racing the
 * same numbers. The only things that clear them are the rider's own Reset and
 * a different wheel connecting, since a different wheel is a different pack
 * and a different motor.
 */
@Singleton
class AccelSplitRepository @Inject constructor() {

    private val tracker = AccelSplitTracker(increment = 10, minSpeed = 20)

    private val _session = MutableStateFlow(AccelSplitTracker.Session())

    /** Best and last-run time for every step seen this session, per direction. */
    val session: StateFlow<AccelSplitTracker.Session> = _session.asStateFlow()

    /**
     * Feed one telemetry sample. [speed] is in the rider's display unit, like
     * the configured step and floor. Returns the steps completed by this
     * sample, for the voice.
     */
    @Synchronized
    fun onSample(cfg: AccelSplitSettings, timeMs: Long, speed: Double): List<AccelSplitTracker.Split> {
        val mode = AccelSplitMode.of(cfg)
        tracker.configure(
            cfg.increment,
            cfg.minSpeed,
            trackAccel = mode == AccelSplitMode.ACCEL || mode == AccelSplitMode.BOTH,
            trackDecel = mode == AccelSplitMode.BRAKE || mode == AccelSplitMode.BOTH,
        )
        val out = tracker.onSample(timeMs, speed)
        // The snapshot is a value, so a frame that changed nothing emits
        // nothing; a completed run updates the "last" column without waiting
        // for the next split to be spoken.
        _session.value = tracker.snapshot()
        return out
    }

    /** Stop tracking and drop the run in flight. Session times are kept. */
    @Synchronized
    fun pause() {
        tracker.pause()
        _session.value = tracker.snapshot()
    }

    /** Forget the session: the rider asked, or a different wheel connected. */
    @Synchronized
    fun reset() {
        tracker.hardReset()
        _session.value = tracker.snapshot()
    }
}
