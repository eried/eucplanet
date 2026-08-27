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
 * The model, in two layers:
 *
 * 1. PHYSICS - each condition gets a raw severity penalty from the rider's
 *    thresholds (the "when does it apply" numbers):
 *      - wind: the dominant hazard, a -2 ramp from breezy to windy then
 *        another -2 by twice windy, capped at 4
 *      - rain / snow: 1 + intensity, capped at 2 each; snow no worse than rain
 *      - cold / hot: 0.08 per degree outside the comfort band, capped 1.5 -
 *        pure temperature is mostly a clothing problem
 *      - night: a flat 0.6
 *      - wet AND windy: +1 more, the combination is what drops a wheel
 *
 * 2. PREFERENCE WEIGHTING - each rider preference scales its condition:
 *      - DISLIKE  x1.00  (the full penalty)
 *      - NEUTRAL  x0.45  (present, but not judged)
 *      - LIKE     converts 35% of a mild penalty (severity up to 2) into a
 *                 bonus of at most +0.7; severity beyond mild still counts
 *                 at neutral weight (a rain rider enjoys drizzle, but a
 *                 storm never reads great)
 *    The wet-and-windy combination keeps a 0.3 physics floor no matter the
 *    preferences: that mix is dangerous whether you like it or not.
 *
 * Comfort bonuses stay physical and ungated by preference: up to +3 for
 * mid-band temperature and +2 for calm air, both only on dry in-band hours.
 * Everything clamps to -5..+5.
 */
object RidabilityScore {

    /** One rider preference for one condition. */
    enum class Pref { DISLIKE, NEUTRAL, LIKE }

    /** The six preferences; defaults mirror [com.eried.eucplanet.data.model.WeatherSettings]. */
    data class Prefs(
        val hot: Pref = Pref.NEUTRAL,
        val cold: Pref = Pref.NEUTRAL,
        val rain: Pref = Pref.DISLIKE,
        val snow: Pref = Pref.DISLIKE,
        val wind: Pref = Pref.DISLIKE,
        val night: Pref = Pref.NEUTRAL,
    )

    fun prefOf(id: String): Pref = when (id) {
        "LIKE" -> Pref.LIKE
        "DISLIKE" -> Pref.DISLIKE
        else -> Pref.NEUTRAL
    }

    /** Settings-string convenience for the six stored ids. */
    fun prefsOf(hot: String, cold: String, rain: String, snow: String, wind: String, night: String) =
        Prefs(prefOf(hot), prefOf(cold), prefOf(rain), prefOf(snow), prefOf(wind), prefOf(night))

    /** A raw penalty signed by the rider's preference for its condition.
     *  LIKE turns 35% of a MILD severity (up to 2) into a bonus, so drizzle
     *  can please a rain rider, but everything past mild still counts at
     *  neutral weight: loving wind never makes a gale read great. */
    private fun weighted(pen: Float, p: Pref): Float = when (p) {
        Pref.DISLIKE -> -pen
        Pref.NEUTRAL -> -0.45f * pen
        Pref.LIKE -> 0.35f * min(pen, 2f) - 0.45f * (pen - 2f).coerceAtLeast(0f)
    }

    /** The combination multiplier: sternest of the two, floored at 0.3. */
    private fun comboMult(p: Pref): Float = when (p) {
        Pref.DISLIKE -> 1f
        Pref.NEUTRAL -> 0.45f
        Pref.LIKE -> 0.3f
    }

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
        prefs: Prefs = Prefs(),
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

        // Physical comfort bonuses: mid-band temperature up to +3, calm air
        // up to +2, both only on a dry in-band hour.
        if (!wet && inBand) s += 3f * (1f - abs(h.tempC - center) / half)
        if (!wet && inBand && h.windMs < breezyMs) {
            s += 2f * (1f - h.windMs / breezyMs)
        }

        // Temperature outside the band, preference-weighted.
        if (!inBand) {
            val dev = if (cold) coldC - h.tempC else h.tempC - hotC
            val pen = min(1.5f, dev * 0.08f)
            s += weighted(pen, if (cold) prefs.cold else prefs.hot)
        }

        // Wind: nothing below breezy, a -2 ramp to the windy threshold, then
        // another -2 by twice that, capped so wind alone never reads worse
        // than wind plus water.
        if (windy) {
            val pen = if (h.windMs <= windyMs) {
                2f * (h.windMs - breezyMs) / windSpan
            } else {
                2f + min(2f, (h.windMs - windyMs) * 0.5f)
            }
            s += weighted(pen, prefs.wind)
        }

        // Water: 1 to 2 by intensity, snow treated no worse than rain.
        if (rain) s += weighted(1f + min(1f, h.precipMmH * 0.5f), prefs.rain)
        if (snow) s += weighted(1f + min(1f, h.snowCmH * 0.5f), prefs.snow)
        // The combination is what drops a wheel: wet AND windy stacks one
        // more, with a physics floor no preference can wash away.
        if (wet && windy) {
            val wetPref = if (snow) prefs.snow else prefs.rain
            s -= 1f * maxOf(0.3f, comboMult(wetPref), comboMult(prefs.wind))
        }

        if (!h.isDay) s += weighted(0.6f, prefs.night)

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
