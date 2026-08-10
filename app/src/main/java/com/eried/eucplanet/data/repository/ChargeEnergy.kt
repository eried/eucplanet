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
     * Wheels throttle their notify rate hard while charging, so the original
     * 5 s cap silently truncated most charging steps and made the Battery
     * screen's Wh figures read far too low. Generous because power while
     * charging is near constant, so integrating a real gap is accurate; the
     * case this actually guards against is a clock jump. A gap that spans a
     * dropped link is handled by re-baselining on reconnect instead, which is
     * exact where a timeout is a guess.
     */
    const val MAX_GAP_MS = 15 * 60_000L

    /**
     * Trapezoidal watt-hours for one step, signed so positive is into the
     * battery regardless of the wheel family's own convention.
     *
     * [charging] takes priority over [dischargeIsPositivePower]. A wheel that
     * reports itself charging is, by definition, taking energy in, so the
     * magnitude is filed that way whatever sign it puts on the reading. That
     * matters because the family flag depends on adapter detection: a V8S that
     * came up on the default adapter would have its whole charge filed as
     * energy OUT, leaving "Charged" at zero and the row hidden, which is
     * exactly what a family-flag-only version did. Sign convention still
     * decides the riding case, where consumption and regen both occur and only
     * the sign separates them.
     *
     * @param prevPowerW  V x I at the previous sample, in the wheel's own sign
     * @param nowPowerW   V x I at this sample, in the wheel's own sign
     * @param dtMs        elapsed since the previous sample
     * @param dischargeIsPositivePower  true for InMotion V1, false elsewhere
     * @param charging    the wheel reports itself charging right now
     */
    fun stepWh(
        prevPowerW: Float,
        nowPowerW: Float,
        dtMs: Long,
        dischargeIsPositivePower: Boolean,
        charging: Boolean = false,
    ): Float {
        if (dtMs <= 0L || dtMs > MAX_GAP_MS) return 0f
        val raw = ((prevPowerW + nowPowerW) * 0.5f) * (dtMs / 3_600_000f)
        if (charging) return kotlin.math.abs(raw)
        return if (dischargeIsPositivePower) -raw else raw
    }
}
