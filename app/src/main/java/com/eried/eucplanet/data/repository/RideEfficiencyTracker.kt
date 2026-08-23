package com.eried.eucplanet.data.repository

/**
 * One tick of the ride-efficiency window: cumulative net energy and cumulative
 * distance, stamped together. Kept as cumulative series rather than per-tick
 * deltas so the rate is a subtraction between two endpoints, which is what
 * makes the numerator and denominator describe the same stretch of road.
 */
data class EnergySample(
    val timestampMs: Long,
    val netWh: Float,
    val cumulativeKm: Float,
)

/**
 * The stateful half of [RideEfficiency]: the rolling window the dashboard's
 * CONSUMPTION and RANGE tiles read from. [RideEfficiency] holds the arithmetic,
 * this holds the samples that arithmetic runs on.
 *
 * Split out of WheelRepository so a whole ride can be replayed against it in a
 * unit test. It is fed one sample per tick and hands back the two numbers the
 * tiles render; it owns no coroutines and touches no Android.
 *
 * Two rules earn their keep:
 *
 * **The session restart is detected on the consumed counter, not the net.**
 * Only [whConsumed] climbs monotonically within a session; the net of consumed
 * and regen falls every time the rider brakes. Watching the net meant ordinary
 * braking looked like a reconnect and wiped the window, so on a V8S city ride
 * the window was cleared a few hundred times an hour and the tiles were blank
 * about two thirds of the time, showing a number only when a stretch happened
 * to survive long enough to cross the distance floor.
 *
 * **The window ages in riding time, not in wall time.** A rate needs ground
 * covered, so ageing samples out by the clock empties the window the moment the
 * wheel stops, which is exactly when a rider looks at the dashboard: that is
 * what "blank after a trip" was. Standing still freezes the window instead, so
 * the tiles keep showing the last five minutes of *riding* through a red light
 * and through the end of a trip. [MAX_HOLD_MS] of standing ends the hold, since
 * by then the window describes a ride that is over.
 */
class RideEfficiencyTracker {

    /** What the CONSUMPTION and RANGE tiles show. NaN in either means blank. */
    data class Estimate(
        val whPerKm: Float = Float.NaN,
        val rangeKm: Float = Float.NaN,
    )

    companion object {
        /** A single 1 Hz step larger than this is a counter reset or a garbled
         *  odometer frame, not 3.6 km/s of riding. */
        const val MAX_STEP_KM = 1f

        /** Longest a standing wheel holds its last window before the tiles go
         *  quiet. Longer than any rolling-window setting, so it only ever ends
         *  a genuine park. */
        const val MAX_HOLD_MS = 30 * 60_000L

        /** Longest gap between samples the riding clock will advance by, so a
         *  stalled tick or a resumed session cannot age the window in one step. */
        private const val MAX_TICK_MS = 5_000L

        /** Float noise on a counter that only climbs; anything past it is a
         *  session that restarted. */
        private const val RESTART_EPSILON_WH = 0.01f
    }

    /** Samples stamped with [ridingMs], not with the wall clock. */
    private val window = ArrayDeque<EnergySample>()
    private var cumulativeKm = 0f
    private var lastSourceKm = Float.NaN
    private var lastWhConsumed = 0f

    /** Clock that only runs while the wheel is covering ground. */
    private var ridingMs = 0L
    private var lastTickMs = 0L
    private var lastMovedMs = 0L

    // Ride-learned pack size: energy spent against percent dropped.
    private var baselineWh = Float.NaN
    private var baselinePct = -1

    /** Forget the session. Called on a reconnect and by the repository. */
    fun reset() {
        window.clear()
        cumulativeKm = 0f
        lastSourceKm = Float.NaN
        lastWhConsumed = 0f
        ridingMs = 0L
        lastTickMs = 0L
        lastMovedMs = 0L
        baselineWh = Float.NaN
        baselinePct = -1
    }

    /**
     * Feed one tick and read the tiles back.
     *
     * @param sourceKm       a cumulative distance counter, odometer for preference
     * @param windowMs       the rider's dashboard rolling window
     * @param packCapacityWh rider-entered pack size, 0 when unset
     */
    fun sample(
        nowMs: Long,
        whConsumed: Float,
        whRegen: Float,
        sourceKm: Float,
        batteryPercent: Int,
        windowMs: Long,
        packCapacityWh: Int,
    ): Estimate {
        // The consumed counter only climbs within a session, so a drop means the
        // session restarted (reconnect) and every baseline is stale.
        if (whConsumed < lastWhConsumed - RESTART_EPSILON_WH) reset()
        lastWhConsumed = whConsumed
        val netWh = whConsumed - whRegen

        // Sum positive steps only, so a counter reset or a source swap restarts
        // the count instead of subtracting a lifetime.
        var stepKm = 0f
        if (!lastSourceKm.isNaN()) {
            val step = sourceKm - lastSourceKm
            if (step > 0f && step < MAX_STEP_KM) stepKm = step
        }
        lastSourceKm = sourceKm
        cumulativeKm += stepKm

        val moving = stepKm > 0f
        if (moving) {
            if (lastTickMs != 0L) ridingMs += (nowMs - lastTickMs).coerceIn(0L, MAX_TICK_MS)
            lastMovedMs = nowMs
        } else if (lastMovedMs != 0L && nowMs - lastMovedMs > MAX_HOLD_MS) {
            // Standing this long is not a pause in a ride, it is the end of one.
            window.clear()
        }
        lastTickMs = nowMs

        if (moving || window.isEmpty()) {
            window.addLast(EnergySample(ridingMs, netWh, cumulativeKm))
        } else {
            // Standing still: the window neither ages nor covers new ground, but
            // the wheel's own idle draw still belongs at the newest end of it.
            window[window.size - 1] = window.last().copy(netWh = netWh)
        }
        while (window.size > 1 && ridingMs - window.first().timestampMs > windowMs) {
            window.removeFirst()
        }

        val whPerKm = RideEfficiency.whPerKm(
            spanWh = window.last().netWh - window.first().netWh,
            spanKm = window.last().cumulativeKm - window.first().cumulativeKm,
        )

        // Charging, or the first reading of a session, rebases.
        if (batteryPercent in 1..100 && (baselinePct < 0 || batteryPercent > baselinePct)) {
            baselinePct = batteryPercent
            baselineWh = netWh
        }
        val whPerPct = RideEfficiency.whPerPercent(netWh, baselineWh, batteryPercent, baselinePct)
        // Until the ride has taught a rate, a rider-entered pack size stands in
        // so the tile answers from the first km. 0 = unset, and the estimate
        // stays blank as before.
        val effectiveWhPerPct =
            if (!whPerPct.isNaN()) whPerPct
            else RideEfficiency.seedWhPerPercent(packCapacityWh)

        return Estimate(
            whPerKm = whPerKm,
            rangeKm = RideEfficiency.rangeKm(whPerKm, effectiveWhPerPct, batteryPercent),
        )
    }
}
