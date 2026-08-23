package com.eried.eucplanet.data.repository

/**
 * Charging inferred from the pack percentage climbing while the wheel stands
 * still, for the families that say nothing else.
 *
 * Most wheels announce a charge, or at least put a negative current on the wire
 * once the charger is plugged in. Neither InMotion generation does: the V14 and
 * P6 sit at about 0 A and a V8S keeps reporting the board's own idle draw
 * through a three-hour charge. For those the percentage rising while parked is
 * the only evidence there is.
 *
 * The percentage is the catch. It arrives in whole numbers, so a charge adding
 * half a point a minute moves it in steps, and a fixed-horizon rate reads either
 * zero or one whole point depending on where the step lands. Measuring across
 * the steps instead of across the clock is what makes the answer stable: wait
 * for the pack to gain [MIN_GAIN_PCT], then divide by however long that took.
 * A pack that has not gained a point within [maxSpanMin] is climbing slower than
 * [onPctPerMin] by definition, which is the same threshold read the other way
 * round.
 *
 * Latching also takes [confirmMs] of climbing without a break. On the V1 family
 * the percentage comes from pack voltage, and voltage rebounds several points in
 * the first minute after a rider steps off; a gain-based window alone clears
 * that in seconds. The rebound is steep but short, so a rule that asks for a few
 * minutes of it never fires, while a charger sustains it all afternoon.
 *
 * Kept free of Android so it can be unit-tested directly.
 */
class ChargeRiseDetector(
    /** Climb per minute at or above which the pack counts as charging. */
    private val onPctPerMin: Float = 0.2f,
    /** How long the climb has to hold up before the state latches on. */
    private val confirmMs: Long = 3 * 60_000L,
) {
    private companion object {
        /** Percentage the pack must gain before a rate is worth computing. One
         *  whole point, which is the finest a wheel reports. */
        const val MIN_GAIN_PCT = 1f
    }

    /** Longest a pack can go without gaining a point and still be charging. */
    private val maxSpanMin = MIN_GAIN_PCT / onPctPerMin

    private var rising = false
    private var refPct = 0f
    private var refMs = 0L
    /** When the current unbroken run of qualifying windows began. */
    private var runStartMs = 0L

    fun reset() {
        rising = false
        refMs = 0L
        runStartMs = 0L
    }

    /**
     * Feed one telemetry frame and read back whether the pack looks like it is
     * charging.
     *
     * [applicable] is false whenever the question does not arise: the wheel is
     * moving, or it states a charge flag of its own, or its current already
     * answers. The detector forgets its reference then, so a ride's motor
     * current cannot leave a stale latch behind for the charge that follows.
     */
    fun update(nowMs: Long, percent: Float, applicable: Boolean): Boolean {
        if (!applicable) {
            reset()
            return false
        }
        if (refMs == 0L) {
            refPct = percent
            refMs = nowMs
        }
        // A pack that drops is being ridden or is just sitting there. Either way
        // it is not charging, and the reference restarts from where it is now.
        if (percent < refPct) {
            refPct = percent
            refMs = nowMs
            runStartMs = 0L
            rising = false
            return false
        }

        // A pack reading full cannot climb any further, so a flat percentage
        // there says nothing about whether the charger is still on. Hold the
        // state instead of reading it as unplugged, which is what put the idle
        // draw back into the Battery screen's "Used" row for the rest of a
        // charge once the pack topped out.
        if (percent >= 100f) {
            refPct = percent
            refMs = nowMs
            return rising
        }

        val spanMin = (nowMs - refMs) / 60_000f
        val gain = percent - refPct
        if (gain >= MIN_GAIN_PCT) {
            val ratePctPerMin = if (spanMin > 0f) gain / spanMin else Float.MAX_VALUE
            refPct = percent
            refMs = nowMs
            if (ratePctPerMin >= onPctPerMin) {
                if (runStartMs == 0L) runStartMs = nowMs
                if (nowMs - runStartMs >= confirmMs) rising = true
            } else {
                runStartMs = 0L
                rising = false
            }
        } else if (spanMin >= maxSpanMin) {
            // A whole window without gaining a point: slower than the threshold.
            refPct = percent
            refMs = nowMs
            runStartMs = 0L
            rising = false
        }
        return rising
    }
}
