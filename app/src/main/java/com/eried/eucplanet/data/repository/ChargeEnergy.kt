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
     * [measuresChargeCurrent] is the other half of that rule. Both InMotion
     * families keep reporting the board's own idle draw while the charger works,
     * so there is no charge current on the wire to integrate. Filing that draw as
     * energy either way is worse than filing nothing: it read as "Used 4 Wh"
     * against +54 % added on a V8S, which is the wheel's standby consumption
     * presented as what the charge did. Charged energy for those wheels comes
     * from the percentage and the pack size instead.
     *
     * @param prevPowerW  V x I at the previous sample, in the wheel's own sign
     * @param nowPowerW   V x I at this sample, in the wheel's own sign
     * @param dtMs        elapsed since the previous sample
     * @param dischargeIsPositivePower  true for InMotion V1, false elsewhere
     * @param charging    the wheel reports itself charging right now
     * @param measuresChargeCurrent  the wheel puts a real charge current on the wire
     */
    fun stepWh(
        prevPowerW: Float,
        nowPowerW: Float,
        dtMs: Long,
        dischargeIsPositivePower: Boolean,
        charging: Boolean = false,
        measuresChargeCurrent: Boolean = true,
    ): Float {
        if (dtMs <= 0L || dtMs > MAX_GAP_MS) return 0f
        if (charging && !measuresChargeCurrent) return 0f
        val raw = ((prevPowerW + nowPowerW) * 0.5f) * (dtMs / 3_600_000f)
        if (charging) return kotlin.math.abs(raw)
        return if (dischargeIsPositivePower) -raw else raw
    }

    /**
     * Charged Wh worked out from the percentage a charge added and the pack's
     * rated size, for the wheels [stepWh] has no charge current to integrate.
     *
     * Rough on purpose, and labelled as an estimate on screen: it is only as
     * good as the wheel's own percentage, and a real pack sags below its
     * nameplate. It is still the only figure those wheels can give, and a rider
     * watching +54 % go by is better served by "about 540 Wh" than by silence.
     * 0 means there is nothing to show: no capacity entered, or nothing added.
     */
    fun chargedWhFromPercent(addedPercent: Float, capacityWh: Int): Float =
        if (addedPercent > 0f && capacityWh > 0) addedPercent / 100f * capacityWh else 0f

    /** Energy over a whole ride, split the way the live buckets are. */
    data class RideEnergy(val outWh: Float, val regenWh: Float) {
        /** What the pack actually lost, which is what a rider means by "used". */
        val netWh: Float get() = outWh - regenWh
    }

    /**
     * Integrate a recorded ride's energy from its samples, using the same step
     * as the live path so a trip's figure and the dashboard's cannot drift.
     *
     * [samples] are (timestamp ms, volts, amps) in recording order. Rows with no
     * voltage or current are skipped rather than read as zero power, which would
     * integrate a phantom idle stretch across them.
     *
     * The family's sign convention is not recorded in the CSV, so it is inferred:
     * integrating both ways, the orientation that produces net consumption is the
     * right one, because a ride always spends more than it regenerates. A trip
     * with no usable rows returns zeroes.
     */
    fun rideEnergy(samples: List<Triple<Long, Float, Float>>): RideEnergy {
        val usable = samples.filter { (_, v, a) -> !v.isNaN() && !a.isNaN() && v > 0f }
        if (usable.size < 2) return RideEnergy(0f, 0f)

        fun integrate(dischargeIsPositivePower: Boolean): RideEnergy {
            var out = 0f
            var regen = 0f
            for (i in 1 until usable.size) {
                val (prevMs, prevV, prevA) = usable[i - 1]
                val (nowMs, nowV, nowA) = usable[i]
                val step = stepWh(prevV * prevA, nowV * nowA, nowMs - prevMs, dischargeIsPositivePower)
                if (step >= 0f) regen += step else out -= step
            }
            return RideEnergy(out, regen)
        }

        val asNegative = integrate(dischargeIsPositivePower = false)
        return if (asNegative.netWh >= 0f) asNegative else integrate(dischargeIsPositivePower = true)
    }
}
