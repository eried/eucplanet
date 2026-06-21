package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.WheelData
import kotlin.math.abs

/**
 * Shared battery-charging estimator — feed it telemetry frames and it tracks the
 * battery trend, detects charging, and predicts ETA to the target / 100 %. Pure
 * Kotlin (no platform deps) so Android + iOS use the SAME algorithm — a faithful,
 * simplified port of the charging logic in Android's WheelRepository.
 *
 * Rate is the % change over a sliding window (default 2 min) → %/min; ETA is the
 * remaining % divided by that rate. Energy (Wh) is the trapezoid integral of
 * |V·I| over the session. Predictions are withheld until the window has "warmed
 * up" (≥1 min of data) so a noisy first sample doesn't show a wild ETA.
 */
class ChargeEstimator(
    private val targetPercent: Float = 80f,
    private val windowMs: Long = 120_000L,
) {
    data class State(
        val charging: Boolean = false,
        val percent: Float = 0f,
        val startPercent: Float = 0f,
        val addedPercent: Float = 0f,
        val ratePctPerMin: Float = 0f,
        val minutesToTarget: Float? = null,
        val minutesToFull: Float? = null,
        val energyWh: Float = 0f,
        val warmedUp: Boolean = false,
    )

    private data class Sample(val t: Long, val pct: Float)
    private val window = ArrayDeque<Sample>()
    private var startPercent = 0f
    private var started = false
    private var energyWh = 0f
    private var lastT = 0L
    private var lastPowerW = 0f

    /** Call when a ride/session ends so the next session starts fresh. */
    fun reset() {
        window.clear(); started = false; energyWh = 0f; lastT = 0L; lastPowerW = 0f; startPercent = 0f
    }

    fun step(d: WheelData): State {
        val t = d.timestamp
        val pct = batteryPercentOf(d)
        if (!started) { startPercent = pct; started = true; lastT = t }

        // Session energy: trapezoid integral of |V·I| (Wh).
        val powerW = abs(d.voltage * d.current)
        if (lastT != 0L && t > lastT) {
            energyWh += (lastPowerW + powerW) * 0.5f * ((t - lastT) / 3_600_000f)
        }
        lastT = t; lastPowerW = powerW

        // Sliding window → smoothed rate (%/min).
        window.addLast(Sample(t, pct))
        while (window.size > 2 && t - window.first().t > windowMs) window.removeFirst()
        val first = window.first()
        val dtMin = (t - first.t) / 60_000f
        val rate = if (dtMin > 0.2f) (pct - first.pct) / dtMin else 0f
        val warmedUp = dtMin >= 1f

        // Charging if the firmware says so OR the pack is rising OR drawing charge
        // current (negative current = into the battery on the inference families).
        val charging = d.charging || rate > 0.02f || (d.current < -0.5f && pct < 99.5f)

        val toTarget = if (charging && rate > 0.001f && pct < targetPercent) (targetPercent - pct) / rate else null
        val toFull = if (charging && rate > 0.001f && pct < 100f) (100f - pct) / rate else null

        return State(
            charging = charging,
            percent = pct,
            startPercent = startPercent,
            addedPercent = pct - startPercent,
            ratePctPerMin = rate,
            minutesToTarget = toTarget?.takeIf { warmedUp },
            minutesToFull = toFull?.takeIf { warmedUp },
            energyWh = energyWh,
            warmedUp = warmedUp,
        )
    }

    /** Average the two packs when both are reported, else the single pack / overall %. */
    private fun batteryPercentOf(d: WheelData): Float = when {
        d.battery1Percent > 0f && d.battery2Percent > 0f -> (d.battery1Percent + d.battery2Percent) / 2f
        d.battery1Percent > 0f -> d.battery1Percent
        else -> d.batteryPercent.toFloat()
    }
}
