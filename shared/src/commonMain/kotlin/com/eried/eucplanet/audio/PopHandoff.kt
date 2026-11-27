package com.eried.eucplanet.audio

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Carries a throttle-chop pop from the telemetry tick to the audio thread, once.
 *
 * The audio thread renders the latest snapshot on every buffer, several times
 * between two ticks. A pop left in the snapshot was laid again on each of them: a
 * burst as long as the wheel is slow to report. The pop is taken out of the
 * snapshot here and given to exactly one buffer. Pops that pile up while the audio
 * thread is held up play as one.
 */
class PopHandoff {
    // A lock where Android had an AtomicBoolean, so the same code runs on the iPhone.
    private val lock = SynchronizedObject()
    private var owed = false

    /** Tick side: keeps the pop aside and returns the snapshot without it. */
    fun post(params: EngineParams): EngineParams {
        if (params.pendingPops <= 0) return params
        synchronized(lock) { owed = true }
        return params.copy(pendingPops = 0)
    }

    /** Audio side: the snapshot for this one buffer, with the pop if one is owed. */
    fun take(params: EngineParams): EngineParams {
        val pop = synchronized(lock) { owed.also { owed = false } }
        return if (pop) params.copy(pendingPops = 1) else params
    }

    /** Drops a pop nobody played, so it cannot go off when the audio starts later. */
    fun clear() = synchronized(lock) { owed = false }
}
