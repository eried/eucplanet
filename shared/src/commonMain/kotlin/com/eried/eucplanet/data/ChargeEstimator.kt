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
        // --- richer charging-monitor fields (Android parity) ---
        val voltage: Float = 0f,
        val current: Float = 0f,
        /** |V·I| when a real (dis)charge current is present; null on ~0 A wheels. */
        val powerW: Int? = null,
        val maxTemp: Float = 0f,
        val battery1: Float = 0f,
        val battery2: Float = 0f,
        val hasPacks: Boolean = false,
        /** Session-long downsampled histories (~1 sample / 10 s) for the curves. */
        val chargeHistory: List<Float> = emptyList(),
        val voltageHistory: List<Float> = emptyList(),
        val tempHistory: List<Float> = emptyList(),
    )

    private data class Sample(val t: Long, val pct: Float)
    private val window = ArrayDeque<Sample>()
    private var startPercent = 0f
    private var started = false
    private var energyWh = 0f
    private var lastT = 0L
    private var lastPowerW = 0f
    private val chargeHist = ArrayList<Float>()
    private val voltHist = ArrayList<Float>()
    private val tempHist = ArrayList<Float>()
    private var lastHistT = 0L
    private var seenPacks = false
    private var seenCurrent = false

    /** Call when a ride/session ends so the next session starts fresh. */
    fun reset() {
        window.clear(); started = false; energyWh = 0f; lastT = 0L; lastPowerW = 0f; startPercent = 0f
        chargeHist.clear(); voltHist.clear(); tempHist.clear(); lastHistT = 0L; seenPacks = false; seenCurrent = false
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

        // Latch packs / real current so the tabs don't flicker on a momentary frame.
        if (d.battery1Percent > 0f && d.battery2Percent > 0f) seenPacks = true
        if (charging && abs(d.current) > 0.5f) seenCurrent = true
        val powerWatts = if (seenCurrent) abs(d.voltage * d.current).toInt() else null

        // Downsample the session curves to ~1 sample / 10 s, capped at ~1 h.
        if (lastHistT == 0L || t - lastHistT >= 10_000L) {
            lastHistT = t
            chargeHist.add(pct); voltHist.add(d.voltage); tempHist.add(d.maxTemperature)
            if (chargeHist.size > 360) { chargeHist.removeAt(0); voltHist.removeAt(0); tempHist.removeAt(0) }
        }

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
            voltage = d.voltage,
            current = d.current,
            powerW = powerWatts,
            maxTemp = d.maxTemperature,
            battery1 = d.battery1Percent,
            battery2 = d.battery2Percent,
            hasPacks = seenPacks,
            chargeHistory = chargeHist.toList(),
            voltageHistory = voltHist.toList(),
            tempHistory = tempHist.toList(),
        )
    }

    /** Average the two packs when both are reported, else the single pack / overall %. */
    private fun batteryPercentOf(d: WheelData): Float = when {
        d.battery1Percent > 0f && d.battery2Percent > 0f -> (d.battery1Percent + d.battery2Percent) / 2f
        d.battery1Percent > 0f -> d.battery1Percent
        else -> d.batteryPercent.toFloat()
    }
}
