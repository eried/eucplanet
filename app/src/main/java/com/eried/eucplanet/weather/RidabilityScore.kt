package com.eried.eucplanet.weather

import kotlin.math.abs
import kotlin.math.min

/**
 * One forecast hour, in the units the scorers think in: metric, straight from
 * the provider. Display conversion happens at the UI, like everywhere else.
 */
data class HourForecast(
    val timeMs: Long,
    val tempC: Float,
    /** Liquid precipitation intensity, mm per hour. */
    val precipMmH: Float,
    /** Snowfall, cm per hour. */
    val snowCmH: Float,
    /** Wind speed, m/s. */
    val windMs: Float,
    val isDay: Boolean,
    /** Relative humidity, percent. 0 when the provider omits it. */
    val humidityPct: Float = 0f,
    /** Wind gust speed, m/s. 0 when the provider omits it. */
    val gustMs: Float = 0f,
)

/**
 * The ridability score, centred on zero: +5 is a perfect riding hour, 0 is
 * neutral - ridable, nothing special - and -5 says leave the wheel home.
 *
 * The philosophy, from the rider's own calibration:
 *  - Wind is the dominant hazard, and rain ON wind is the killer combination:
 *    strong wind plus rain bottoms out at -5.
 *  - Rain or snow alone are manageable (-1 to -2); snow is not the disaster
 *    the old scale made it - snow plus cold with no wind is only about -1.
 *  - Temperature ALONE barely moves the needle: -10 C on a calm clear day is
 *    near 0 - you dress for it. Inside the comfort band it turns into a
 *    bonus, peaking mid-band.
 *  - Bonuses (comfortable temperature, calm air) only count in genuinely
 *    good conditions: any precipitation cancels them, and calm air earns
 *    nothing when the temperature is outside the band.
 *
 * All four thresholds are the rider's comfort settings.
 */
object RidabilityScore {

    /** Score plus which factors bit, for the graph's faces and glyph strip. */
    data class Breakdown(
        val score: Float,
        val cold: Boolean,
        val hot: Boolean,
        val rain: Boolean,
        val snow: Boolean,
        val wind: Boolean,
        val night: Boolean,
    )

    fun score(
        h: HourForecast,
        coldC: Float,
        hotC: Float,
        breezyMs: Float,
        windyMs: Float,
    ): Breakdown {
        val center = (coldC + hotC) / 2f
        val half = ((hotC - coldC) / 2f).coerceAtLeast(0.5f)
        val inBand = h.tempC in coldC..hotC

        val snow = h.snowCmH > 0f
        val rain = !snow && h.precipMmH > 0f
        val wet = rain || snow
        val cold = h.tempC < coldC
        val hot = h.tempC > hotC
        val windSpan = (windyMs - breezyMs).coerceAtLeast(0.1f)
        val windy = h.windMs > breezyMs

        var s = 0f

        // Comfortable temperature is worth up to +3 at mid-band, fading to 0
        // at the band edges. Outside the band the penalty is deliberately
        // shallow and capped: pure temperature is a clothing problem.
        if (!wet && inBand) s += 3f * (1f - abs(h.tempC - center) / half)
        if (!inBand) {
            val dev = if (cold) coldC - h.tempC else h.tempC - hotC
            s -= min(0.5f, dev * 0.03f)
        }

        // Calm air is worth up to +2, but only when it is also a comfortable,
        // dry hour - calm alone does not make a freezing day good.
        if (!wet && inBand && h.windMs < breezyMs) {
            s += 2f * (1f - h.windMs / breezyMs)
        }

        // Wind, the dominant hazard: nothing below breezy, a -2 ramp to the
        // windy threshold, then another -2 by twice that. Capped at -4 so
        // wind alone never reads worse than wind plus water.
        if (windy) {
            s -= if (h.windMs <= windyMs) {
                2f * (h.windMs - breezyMs) / windSpan
            } else {
                2f + min(2f, (h.windMs - windyMs) * 0.5f)
            }
        }

        // Water: -1 to -2 by intensity, snow treated no worse than rain.
        if (rain) s -= 1f + min(1f, h.precipMmH * 0.5f)
        if (snow) s -= 1f + min(1f, h.snowCmH * 0.5f)
        // The combination is what drops a wheel: wet AND windy stacks -1 more.
        if (wet && windy) s -= 1f

        if (!h.isDay) s -= 0.5f

        return Breakdown(
            score = s.coerceIn(-5f, 5f),
            cold = cold,
            hot = hot,
            rain = rain,
            snow = snow,
            wind = windy,
            night = !h.isDay,
        )
    }
}
