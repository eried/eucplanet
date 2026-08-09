package com.eried.eucplanet.data.repository

/**
 * Energy integration for the charging session.
 *
 * Wheels do not agree on which direction of power is positive. InMotion V1
 * (V8S and family) reports discharge as POSITIVE power, its idle +3 W matches
 * EUC World and V x I exactly, so its charge current is negative. InMotion V2
 * (V14) and the other decoded families are the other way round.
 *
 * [stepWh] resolves that here, once, so the accumulators downstream mean what
 * their names say: positive is energy INTO the battery, negative is energy out
 * of it. Consumers must not re-apply a family flip of their own.
 *
 * Kept free of Android so it can be unit-tested directly.
 */
object ChargeEnergy {

    /**
     * Longest gap between samples still treated as continuous.
     *
     * Wheels throttle their notify rate hard while charging, so the previous
     * 5 s cap silently truncated most charging steps and made the Battery
     * screen's Wh figures read far too low. Anything longer than this is a
     * break in the link rather than a slow sample, and contributes nothing
     * instead of contributing a wrong partial amount.
     */
    const val MAX_GAP_MS = 60_000L

    /**
     * Trapezoidal watt-hours for one step, signed so positive is into the
     * battery regardless of the wheel family's own convention.
     *
     * @param prevPowerW  V x I at the previous sample, in the wheel's own sign
     * @param nowPowerW   V x I at this sample, in the wheel's own sign
     * @param dtMs        elapsed since the previous sample
     * @param dischargeIsPositivePower  true for InMotion V1, false elsewhere
     */
    fun stepWh(
        prevPowerW: Float,
        nowPowerW: Float,
        dtMs: Long,
        dischargeIsPositivePower: Boolean,
    ): Float {
        if (dtMs <= 0L || dtMs > MAX_GAP_MS) return 0f
        val raw = ((prevPowerW + nowPowerW) * 0.5f) * (dtMs / 3_600_000f)
        return if (dischargeIsPositivePower) -raw else raw
    }
}
