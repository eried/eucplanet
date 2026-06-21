package com.eried.eucplanet.nav

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Distance formatting for the Navigator, in the rider's unit system. Provides
 * both an on-screen form ("200 m", "1.4 km") and a spoken form ("200 meters",
 * "1.4 kilometers") so TTS reads naturally. Faithful port of Android's NavFormat;
 * the unit words mirror the default-locale nav_dist / voice_dist strings.
 */
object NavFormat {

    private const val FEET_PER_M = 3.28084
    private const val M_PER_MILE = 1609.34

    /** Compact distance for the popup, e.g. "200 m" / "1.4 km" / "300 ft" / "0.8 mi". */
    fun distance(meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) "${roundStep(feet)} ft"
            else "${oneDecimal(meters / M_PER_MILE)} mi"
        }
        return if (meters < 1000) "${roundStep(meters)} m"
        else "${oneDecimal(meters / 1000.0)} km"
    }

    /** Same value spelled out for TTS, e.g. "200 meters" / "1.4 kilometers". */
    fun spokenDistance(meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) "${roundStep(feet)} feet"
            else "${oneDecimal(meters / M_PER_MILE)} miles"
        }
        return if (meters < 1000) "${roundStep(meters)} meters"
        else "${oneDecimal(meters / 1000.0)} kilometers"
    }

    /** Rounds short distances to a tidy step so the popup doesn't jitter by the meter. */
    private fun roundStep(value: Double): Int {
        val step = if (value < 100) 5 else 10
        return ((value / step).roundToInt() * step).coerceAtLeast(0)
    }

    /** One-decimal string without java's String.format (KMP-safe), e.g. 1.43 -> "1.4". */
    private fun oneDecimal(value: Double): String {
        val r = (value * 10).roundToInt()
        return "${r / 10}.${abs(r % 10)}"
    }
}
