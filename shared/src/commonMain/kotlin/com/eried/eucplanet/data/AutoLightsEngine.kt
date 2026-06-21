package com.eried.eucplanet.data

import com.eried.eucplanet.location.GpsFix
import com.eried.eucplanet.util.SunCalculator

/**
 * Sun + GPS based auto-lights — faithful port of the light logic in Android's
 * `AutomationManager`. Pure Kotlin: the app feeds the current fix + light state
 * each tick and acts on the returned desired state. Throttled like Android:
 * re-evaluate every 60 s with a fix, every 2 s while waiting for one.
 */
class AutoLightsEngine {
    private var lastCheckMs = 0L

    /**
     * Desired headlight state for now, or null when there's nothing to do this
     * tick (throttled, or no GPS fix yet). PolarNight ⇒ always on, MidnightSun ⇒
     * always off, otherwise on between (sunset − [onMinutesBefore]) and
     * (sunrise + [offMinutesAfter]).
     */
    fun desiredState(fix: GpsFix?, onMinutesBefore: Int, offMinutesAfter: Int, nowMs: Long): Boolean? {
        val interval = if (fix == null) 2_000L else 60_000L
        if (nowMs - lastCheckMs < interval) return null
        lastCheckMs = nowMs
        if (fix == null) return null
        return when (val r = SunCalculator.calculateState(fix.lat, fix.lng)) {
            is SunCalculator.SunResult.Normal -> {
                val onTime = r.sunsetMillis - onMinutesBefore * 60_000L
                val offTime = r.sunriseMillis + offMinutesAfter * 60_000L
                nowMs >= onTime || nowMs <= offTime
            }
            SunCalculator.SunResult.PolarNight -> true
            SunCalculator.SunResult.MidnightSun -> false
        }
    }

    /** Re-arm so the next tick evaluates immediately (e.g. on reconnect). */
    fun reset() { lastCheckMs = 0L }
}
