package com.eried.eucplanet.nav

import android.content.Context
import com.eried.eucplanet.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Distance formatting for the Navigator, in the rider's unit system. Provides
 * both an on-screen form ("200 m", "1.4 km") and a spoken form ("200 meters",
 * "1.4 kilometers") so TTS reads naturally.
 */
object NavFormat {

    private const val FEET_PER_M = 3.28084
    private const val M_PER_MILE = 1609.34

    /** Compact distance for the popup, e.g. "200 m" / "1.4 km" / "300 ft" / "0.8 mi". */
    fun distance(context: Context, meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) {
                context.getString(R.string.nav_dist_ft, roundStep(feet))
            } else {
                context.getString(R.string.nav_dist_mi, oneDecimal(meters / M_PER_MILE))
            }
        }
        return if (meters < 1000) {
            context.getString(R.string.nav_dist_m, roundStep(meters))
        } else {
            context.getString(R.string.nav_dist_km, oneDecimal(meters / 1000.0))
        }
    }

    /** Same value spelled out for TTS, e.g. "200 meters" / "1.4 kilometers". */
    fun spokenDistance(context: Context, meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) {
                context.getString(R.string.voice_dist_ft, roundStep(feet))
            } else {
                context.getString(R.string.voice_dist_mi, oneDecimal(meters / M_PER_MILE))
            }
        }
        return if (meters < 1000) {
            context.getString(R.string.voice_dist_m, roundStep(meters))
        } else {
            context.getString(R.string.voice_dist_km, oneDecimal(meters / 1000.0))
        }
    }

    /** Rounds short distances to a tidy step so the popup doesn't jitter by the meter. */
    private fun roundStep(value: Double): Int {
        val step = if (value < 100) 5 else 10
        return ((value / step).roundToInt() * step).coerceAtLeast(0)
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}
