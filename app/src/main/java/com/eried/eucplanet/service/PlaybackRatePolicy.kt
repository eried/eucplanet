package com.eried.eucplanet.service

/**
 * Speed-linked playback rate, as a pure function of speed and the last rate
 * actually sent.
 *
 * The curve is the rider's, in the same "speed:value" shape auto-volume uses,
 * so the same editor drives both. This layer only decides WHEN a new rate is
 * worth sending, which matters because every send crosses a binder to another
 * app's media session: a rider holding 24 km/h with normal wheel jitter would
 * otherwise generate a stream of 1.147, 1.151, 1.149 that no ear can hear and
 * some players redraw their UI for.
 *
 * Two gates, both deliberately coarse:
 *  - [STEP]: the rate must differ from the last sent one by a real amount.
 *  - [MIN_INTERVAL_MS]: and not sooner than this after the last send, so an
 *    acceleration ramps the rate in steps rather than continuously.
 *
 * Kept free of Android so it can be unit-tested directly, like
 * [MediaControlPolicy] beside it.
 */
object PlaybackRatePolicy {

    /** What players accept, and what a rider can still follow. */
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f

    /** Smallest rate change worth sending. Below this nobody hears it. */
    const val STEP = 0.05f

    /** Quiet time between sends. */
    const val MIN_INTERVAL_MS = 2000L

    /** Rate everything returns to when the feature stops. */
    const val NORMAL = 1.0f

    data class State(
        /** The last rate this feature sent, 0 when it has sent none. */
        val lastSent: Float = 0f,
        val lastAtMs: Long = 0L,
    )

    /** [rate] non-null means "send this now". */
    data class Step(val state: State, val rate: Float?)

    /**
     * [target] is the curve's value for the current speed, unclamped; this
     * rounds it to the step grid so repeated passes at the same speed produce
     * the same number rather than drifting by fractions.
     */
    fun step(state: State, target: Float, nowMs: Long): Step {
        val snapped = snap(target)
        val first = state.lastSent <= 0f
        val moved = kotlin.math.abs(snapped - state.lastSent) >= STEP - 1e-4f
        val quiet = nowMs - state.lastAtMs >= MIN_INTERVAL_MS
        // The first send goes immediately: the rider just enabled the feature
        // or just started playing, and waiting two seconds to apply a rate
        // they can see configured reads as the switch not working.
        if (!first && (!moved || !quiet)) return Step(state, null)
        return Step(State(lastSent = snapped, lastAtMs = nowMs), snapped)
    }

    /** Rounds onto the [STEP] grid and clamps into what players accept. */
    fun snap(rate: Float): Float {
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        return (Math.round(clamped / STEP) * STEP).coerceIn(MIN_RATE, MAX_RATE)
    }
}
