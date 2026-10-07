package com.eried.eucplanet.service

/**
 * Speed-based media pause and resume, as a pure state machine.
 *
 * Pause fires once when the rider slows to the pause speed and holds there;
 * it does not fire again while they stay slow. It re-arms only when they have
 * been fast again - at or above the resume speed - so a stop is always the
 * edge from fast to slow, never the level. Resume, when enabled, restarts only
 * what this feature paused.
 *
 * The re-arm used to live inside the resume rule, so with resume switched off
 * the pause fired exactly once per ride and then never again: the rider would
 * start their music by hand, slow down for the next crossing, and nothing
 * happened. Re-arming is its own step now and does not need resume.
 */
object MediaControlPolicy {

    /** The speed condition must hold this long before acting, so a blip does nothing. */
    const val HOLD_MS = 3000L

    /** Dead-band enforced between the pause and resume speeds, even if set equal. */
    const val MIN_GAP_KMH = 2

    enum class Action { NONE, PAUSE, PLAY }

    data class State(
        /** This feature paused playback and may resume it; a rider's own pause is never ours. */
        val autoPaused: Boolean = false,
        val pauseSince: Long = 0L,
        val resumeSince: Long = 0L,
    )

    data class Step(val state: State, val action: Action)

    fun step(
        state: State,
        speedKmh: Float,
        nowMs: Long,
        pauseEnabled: Boolean,
        pauseBelowKmh: Int,
        resumeEnabled: Boolean,
        resumeAboveKmh: Int,
        /** Audio is on headphones or Bluetooth, or the rider does not care. */
        externalOk: Boolean,
        connected: Boolean,
    ): Step {
        // A disconnected wheel reports 0 km/h, which would pause the music the
        // moment the rider walks off with the phone. Timers reset; the paused
        // flag stays, so a reconnect cannot resume music the rider stopped.
        if (!connected) return Step(state.copy(pauseSince = 0L, resumeSince = 0L), Action.NONE)

        val resumeAt = maxOf(resumeAboveKmh, pauseBelowKmh + MIN_GAP_KMH)
        var s = state
        var action = Action.NONE

        // Slow, and not already paused by us: pause after the hold.
        if (pauseEnabled && !s.autoPaused && speedKmh <= pauseBelowKmh) {
            val since = if (s.pauseSince == 0L) nowMs else s.pauseSince
            if (nowMs - since >= HOLD_MS) {
                action = Action.PAUSE
                s = s.copy(autoPaused = true, pauseSince = 0L)
            } else {
                s = s.copy(pauseSince = since)
            }
        } else {
            s = s.copy(pauseSince = 0L)
        }

        // Fast again, after a pause of ours: re-arm, and resume if asked.
        //
        // With resume on, the external-output check can hold this back (a
        // resume on the phone speaker is the risky half), and while it does the
        // pause stays armed-off and keeps waiting - plugging in headphones at
        // speed still resumes. With resume off there is nothing to wait for,
        // so the crossing simply re-arms the pause.
        if (s.autoPaused && speedKmh >= resumeAt && (!resumeEnabled || externalOk)) {
            val since = if (s.resumeSince == 0L) nowMs else s.resumeSince
            if (nowMs - since >= HOLD_MS) {
                if (resumeEnabled) action = Action.PLAY
                s = s.copy(autoPaused = false, resumeSince = 0L)
            } else {
                s = s.copy(resumeSince = since)
            }
        } else {
            s = s.copy(resumeSince = 0L)
        }
        return Step(s, action)
    }
}
