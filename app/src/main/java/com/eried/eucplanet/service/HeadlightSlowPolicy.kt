package com.eried.eucplanet.service

/**
 * Kills the headlight when the rider slows to a walk, as a pure state machine.
 *
 * The sun schedule says whether it is dark enough to need a light; this says
 * whether the rider is still riding. Rolling to a stop at a crossing with the
 * beam in someone's face is the case it exists for, and the light comes back
 * on its own once they are moving again, without touching the schedule.
 *
 * Both edges are deliberately sticky. The speed must hold below the threshold
 * for [HOLD_MS] before the light goes out, so a slow corner does not flick it;
 * and coming back needs [REARM_GAP_KMH] above the threshold, so a rider
 * hovering either side of walking pace does not strobe.
 *
 * Kept free of Android so it can be unit-tested, like [MediaControlPolicy]
 * and [PlaybackRatePolicy] beside it.
 */
object HeadlightSlowPolicy {

    /** How long the rider must stay slow before the light goes out. */
    const val HOLD_MS = 3000L

    /** How far above the threshold counts as riding again. */
    const val REARM_GAP_KMH = 2f

    data class State(
        /** This feature is holding the light off; the schedule is on hold. */
        val forcedOff: Boolean = false,
        val slowSince: Long = 0L,
    )

    /**
     * [enabled] false returns a cleared state, so switching the option off
     * hands the light straight back to the sun schedule rather than leaving
     * it dark until the rider speeds up.
     */
    fun step(
        state: State,
        speedKmh: Float,
        thresholdKmh: Float,
        nowMs: Long,
        enabled: Boolean,
    ): State {
        if (!enabled) return State()
        if (state.forcedOff) {
            // Only genuine riding releases it.
            return if (speedKmh >= thresholdKmh + REARM_GAP_KMH) State() else state
        }
        if (speedKmh >= thresholdKmh) return state.copy(slowSince = 0L)
        val since = if (state.slowSince == 0L) nowMs else state.slowSince
        return if (nowMs - since >= HOLD_MS) State(forcedOff = true, slowSince = since)
        else state.copy(slowSince = since)
    }

    /**
     * What the beam should do, from the schedule's answer and the cutoff.
     *
     * The cutoff wins outright. "It is after sunset" is a reason to want a
     * light; it is not a reason to keep one pointed at whoever is waiting at
     * the crossing with you, so slowing to a walk takes it off whatever the
     * schedule says.
     *
     * It also does not need the schedule to have an answer. [darkEnough] is
     * null until a location fix arrives, and null means "leave the light
     * alone" for the ON half only: with no fix and no cutoff there is nothing
     * to say, but with the cutoff engaged there certainly is.
     */
    fun beamOn(darkEnough: Boolean?, forcedOff: Boolean): Boolean? =
        if (forcedOff) false else darkEnough
}