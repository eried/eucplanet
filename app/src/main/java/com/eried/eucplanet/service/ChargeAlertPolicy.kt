package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ChargeStatus

/**
 * When to tell the rider their pack has hit a mark.
 *
 * Three alerts, all off unless asked for: eighty percent, which is where a
 * rider who charges for pack life unplugs, full, and a level the rider types
 * in, 60 unless changed, for a wheel that will sit for weeks and keeps best
 * around half.
 *
 * The whole difficulty is firing once. Telemetry arrives several times a
 * second, a pack sitting at 80% would otherwise ping on every frame, and a
 * charger tapering near the top crosses the same point repeatedly. So each
 * alert fires at most once per charging session, and a session ends when the
 * pack leaves the charger.
 *
 * Android-free so the matrix can be tested directly.
 */
object ChargeAlertPolicy {

    /** The mark riders use for longevity. Not configurable: this is the number
     *  the whole convention is built around, and a slider here would be a
     *  setting nobody moves. */
    const val THRESHOLD_PCT = 80

    /** The rider's own level: where it starts, and what it may be. Never 80,
     *  which has its own alert, so one level cannot ping twice. */
    const val CUSTOM_DEFAULT_PCT = 60
    val CUSTOM_RANGE = 5..95

    fun isValidCustom(pct: Int): Boolean = pct in CUSTOM_RANGE && pct != THRESHOLD_PCT

    /** What a stored level becomes: a synced file cannot ask for 80 or 300. */
    fun sanitizeCustom(pct: Int): Int {
        val p = pct.coerceIn(CUSTOM_RANGE)
        return if (p == THRESHOLD_PCT) CUSTOM_DEFAULT_PCT else p
    }

    enum class Alert { NONE, AT_80, FULL, CUSTOM }

    data class State(
        /** True once this session has been seen below the mark. Without it, a
         *  pack plugged in at 85% would announce that it had "reached" 80. */
        val sawBelowMark: Boolean = false,
        /** True once this session has been seen actually charging. Parking a
         *  full wheel on the charger is not a charge completing. */
        val sawCharging: Boolean = false,
        val firedMark: Boolean = false,
        val firedFull: Boolean = false,
        /** The lowest level this session has shown, so the rider's own mark,
         *  which can change mid-charge, is only "reached" from below. */
        val lowest: Int = 101,
        val firedCustom: Boolean = false,
    )

    data class Step(val alert: Alert, val state: State)

    /**
     * One telemetry frame.
     *
     * [want80] and [wantFull] are the rider's toggles; they gate the alert, not
     * the bookkeeping, so turning one on mid-charge does not immediately fire
     * for a mark that was passed before it was switched on. [customMark] is
     * the rider's own level, 0 for off.
     */
    fun step(
        state: State,
        status: ChargeStatus,
        percent: Int,
        want80: Boolean,
        wantFull: Boolean,
        customMark: Int = 0,
    ): Step {
        // Off the charger: the session is over and the next one starts clean.
        if (status == ChargeStatus.Disconnected || status == ChargeStatus.Idle) {
            return Step(Alert.NONE, State())
        }

        var s = state
        if (status == ChargeStatus.Charging) s = s.copy(sawCharging = true)
        if (percent < THRESHOLD_PCT) s = s.copy(sawBelowMark = true)
        // Before a jump past the rider's mark counts, it must have been below.
        val wasBelowCustom = customMark > 0 && s.lowest < customMark
        if (percent < s.lowest) s = s.copy(lowest = percent)

        // Full first: a pack that jumps straight from 79 to full in one frame
        // has completed, and that is the more interesting of the two.
        if (status == ChargeStatus.Full && s.sawCharging && !s.firedFull) {
            s = s.copy(firedFull = true, firedMark = true, firedCustom = true)
            return Step(if (wantFull) Alert.FULL else Alert.NONE, s)
        }

        // The rider's mark before 80: set for storage it is the lower one,
        // and if 80 was passed in the same frame it still fires on the next.
        if (wasBelowCustom && percent >= customMark && !s.firedCustom) {
            s = s.copy(firedCustom = true)
            return Step(Alert.CUSTOM, s)
        }

        if (percent >= THRESHOLD_PCT && s.sawBelowMark && !s.firedMark) {
            s = s.copy(firedMark = true)
            return Step(if (want80) Alert.AT_80 else Alert.NONE, s)
        }

        return Step(Alert.NONE, s)
    }
}
