package com.eried.eucplanet.service

/**
 * Everything that decides the headlight, in one place.
 *
 * There are four rules now and they interact: when the automation applies at
 * all, the sun schedule, the walking-pace cutoff, and whether the rider has
 * taken the light over by hand. Spread across a service method they could only
 * be checked by reading, and two of them had already gone wrong that way, so
 * the composition lives here where the whole matrix can be tested.
 *
 * Deliberately NOT here: the manual-override suspension, which is the caller's
 * to apply because it outlives any single decision, the throttling of the sun
 * calculation, and the cooldown between commands to the wheel. Those are about
 * timing and side effects rather than about what the beam should be doing.
 */
object HeadlightRules {

    data class Input(
        /** [com.eried.eucplanet.data.model.ApplyWhenIds] value. */
        val applyWhen: String,
        val connected: Boolean,
        /** Has the rider actually ridden since connecting. */
        val rodeThisSession: Boolean,
        val offWhenSlow: Boolean,
        val thresholdKmh: Float,
        val speedKmh: Float,
        /** The sun schedule's answer, or null when no fix has arrived yet. */
        val darkEnough: Boolean?,
        val nowMs: Long,
    )

    data class Decision(
        val slow: HeadlightSlowPolicy.State,
        /** True on, false off, null leave the beam exactly as it is. */
        val beamOn: Boolean?,
    )

    fun decide(slow: HeadlightSlowPolicy.State, input: Input): Decision {
        // Sticky, because a rider waiting at a crossing has a speed of zero and
        // the plain "is it moving" test would switch the automation off at the
        // moment the cutoff exists for.
        if (!ApplyWhenGate.allowsSticky(input.applyWhen, input.connected, input.rodeThisSession)) {
            // Hands off entirely: whatever the beam is doing is the rider's,
            // or the last thing the automation left it doing. The cutoff state
            // is kept, so re-entering a ride does not start from a stale slow.
            return Decision(slow, null)
        }
        val next = HeadlightSlowPolicy.step(
            state = slow,
            speedKmh = input.speedKmh,
            thresholdKmh = input.thresholdKmh,
            nowMs = input.nowMs,
            enabled = input.offWhenSlow,
        )
        return Decision(next, HeadlightSlowPolicy.beamOn(input.darkEnough, next.forcedOff))
    }
}
