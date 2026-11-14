package com.eried.eucplanet.nav

import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.nav_dist_ft
import com.eried.eucplanet.resources.nav_dist_km
import com.eried.eucplanet.resources.nav_dist_m
import com.eried.eucplanet.resources.nav_dist_mi
import com.eried.eucplanet.resources.voice_dist_ft
import com.eried.eucplanet.resources.voice_dist_km
import com.eried.eucplanet.resources.voice_dist_m
import com.eried.eucplanet.resources.voice_dist_mi
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

/**
 * Distance formatting for the Navigator, in the rider's unit system. Provides
 * both an on-screen form ("200 m", "1.4 km") and a spoken form ("200 meters",
 * "1.4 kilometers") so TTS reads naturally.
 *
 * [text] reads a string with its arguments: `rememberStringFormatter()` in a
 * composable, the engine's own lookup elsewhere.
 */
object NavFormat {

    private const val FEET_PER_M = 3.28084
    private const val M_PER_MILE = 1609.34

    /** Compact distance for the popup, e.g. "200 m" / "1.4 km" / "300 ft" / "0.8 mi". */
    fun distance(text: (StringResource, List<Any>) -> String, meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) {
                text(Res.string.nav_dist_ft, listOf(roundStep(feet)))
            } else {
                text(Res.string.nav_dist_mi, listOf(oneDecimal(meters / M_PER_MILE)))
            }
        }
        return if (meters < 1000) {
            text(Res.string.nav_dist_m, listOf(roundStep(meters)))
        } else {
            text(Res.string.nav_dist_km, listOf(oneDecimal(meters / 1000.0)))
        }
    }

    /** Same value spelled out for TTS, e.g. "200 meters" / "1.4 kilometers". */
    fun spokenDistance(text: (StringResource, List<Any>) -> String, meters: Double, imperial: Boolean): String {
        if (imperial) {
            val feet = meters * FEET_PER_M
            return if (feet < 1000) {
                text(Res.string.voice_dist_ft, listOf(roundStep(feet)))
            } else {
                text(Res.string.voice_dist_mi, listOf(oneDecimal(meters / M_PER_MILE)))
            }
        }
        return if (meters < 1000) {
            text(Res.string.voice_dist_m, listOf(roundStep(meters)))
        } else {
            text(Res.string.voice_dist_km, listOf(oneDecimal(meters / 1000.0)))
        }
    }

    /** Rounds short distances to a tidy step so the popup doesn't jitter by the meter. */
    private fun roundStep(value: Double): Int {
        val step = if (value < 100) 5 else 10
        return ((value / step).roundToInt() * step).coerceAtLeast(0)
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}
