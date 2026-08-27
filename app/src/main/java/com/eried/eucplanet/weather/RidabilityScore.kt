package com.eried.eucplanet.weather

import kotlin.math.max
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
    /** Inside the golden window around sunrise or sunset. See [SunCalc]. */
    val isGolden: Boolean = false,
)

/**
 * The ridability score, -5..+5: +5 a drop-everything riding hour, 0 fine
 * with the right gear, -5 leave the wheel home.
 *
 * DEFICIT MODEL. Every hour starts perfect at +5 and only loses points for
 * real discomforts, each a curve that is near zero across a broad "perfectly
 * fine" region and accelerates toward genuine hazard. That is what makes the
 * whole scale reachable in any city on Earth: a genuinely nice local day
 * (roughly comfort-band temperature, light wind, dry) costs almost nothing
 * and tops out, instead of demanding one exact perfect combination.
 *
 * The deficits (defaults in parentheses; the four thresholds are the rider's
 * Advanced settings):
 *  - temperature: free between coldC+3 and hotC-4 (17..27); the cold side
 *    costs 0.10/deg + 0.004/deg^2, the hot side 0.18/deg + 0.02/deg^2.
 *    When humidity is known and it is warm, the hot side runs on apparent
 *    temperature (T + 0.05 * max(0, RH - 55)).
 *  - wind: effective wind W' = wind + half the gust excess. Free below
 *    breezy+0.5; then 0.30/ms + 0.15/ms^2, capped at 7. A cold-wind chill
 *    term adds up to 1.5 when it is cold AND windy.
 *  - rain: 1 + 1.5 * min(1, mm/2); snow: 1 + 1.5 * min(1, cm/2) plus 1.2
 *    when at or below freezing. Wet AND past the windy threshold adds 1.5.
 *  - night: a flat 0.6.
 *  - traces below 0.15 mm/h rain or 0.05 cm/h snow count as dry, so
 *    provider noise can never flip the score.
 *
 * Preferences scale each condition RELATIVE TO ITS SHIPPED DEFAULT (rain,
 * snow and wind ship disliked; heat, cold and night neutral), so the
 * rider's calibration anchors hold exactly at defaults:
 *  - hazard conditions (rain/snow/wind): dislike x1.0, neutral x0.6,
 *    like softens to 30% and adds a small mild-severity bonus (capped 0.7).
 *  - comfort conditions (heat/cold/night): neutral x1.0, dislike x1.5,
 *    like as above.
 *  - golden hour stays opposite polarity: like +1.5, neutral 0, dislike -1.
 *
 * SAFETY, preference-proof and applied last (no Like can buy them back):
 *  - measurable rain caps the score at +1, snow at 0 (wet pavement is wet);
 *  - wet plus near-gale (W' >= windy + 3.5) pins to -5;
 *  - liquid rain at or below +1.5 C (glaze ice) pins to -4 or lower;
 *  - gusts reaching 17 m/s pin to -4 or lower.
 * Everything clamps to -5..+5.
 */
object RidabilityScore {

    /** One rider preference for one condition. */
    enum class Pref { DISLIKE, NEUTRAL, LIKE }

    /** The seven preferences; defaults mirror [com.eried.eucplanet.data.model.WeatherSettings]. */
    data class Prefs(
        val hot: Pref = Pref.NEUTRAL,
        val cold: Pref = Pref.NEUTRAL,
        val rain: Pref = Pref.DISLIKE,
        val snow: Pref = Pref.DISLIKE,
        val wind: Pref = Pref.DISLIKE,
        val night: Pref = Pref.NEUTRAL,
        val golden: Pref = Pref.NEUTRAL,
    )

    fun prefOf(id: String): Pref = when (id) {
        "LIKE" -> Pref.LIKE
        "DISLIKE" -> Pref.DISLIKE
        else -> Pref.NEUTRAL
    }

    /** Settings-string convenience for the stored ids. */
    fun prefsOf(
        hot: String, cold: String, rain: String, snow: String,
        wind: String, night: String, golden: String,
    ) = Prefs(
        prefOf(hot), prefOf(cold), prefOf(rain), prefOf(snow),
        prefOf(wind), prefOf(night), prefOf(golden),
    )

    /** Liking a condition keeps only 30% of its deficit and earns a small
     *  bonus while it stays mild; severity ordering stays monotone. */
    private fun likedDeficit(pen: Float): Float =
        0.3f * pen - min(0.7f, 0.35f * min(pen, 2f))

    /** Hazard conditions ship DISLIKED: dislike is the calibrated baseline. */
    private fun hazardDeficit(pen: Float, p: Pref): Float = when (p) {
        Pref.DISLIKE -> pen
        Pref.NEUTRAL -> 0.6f * pen
        Pref.LIKE -> likedDeficit(pen)
    }

    /** Comfort conditions ship NEUTRAL: neutral is the calibrated baseline. */
    private fun comfortDeficit(pen: Float, p: Pref): Float = when (p) {
        Pref.DISLIKE -> 1.5f * pen
        Pref.NEUTRAL -> pen
        Pref.LIKE -> likedDeficit(pen)
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
        val golden: Boolean = false,
    )

    fun score(
        h: HourForecast,
        coldC: Float,
        hotC: Float,
        breezyMs: Float,
        windyMs: Float,
        prefs: Prefs = Prefs(),
    ): Breakdown {
        // Effective wind: sustained plus half the gust excess, so a squally
        // hour reads harder than a steady one at the same mean.
        val wEff = h.windMs + 0.5f * max(0f, h.gustMs - h.windMs)

        // Trace deadband: provider drizzle noise never flips the score.
        val snow = h.snowCmH >= 0.05f
        val rain = !snow && h.precipMmH >= 0.15f
        val wet = rain || snow

        // Apparent temperature on the warm side when humidity is known.
        val tEff = if (h.humidityPct > 0f && h.tempC >= 26f) {
            h.tempC + 0.05f * max(0f, h.humidityPct - 55f)
        } else h.tempC

        val plateauLo = coldC + 3f
        val plateauHi = hotC - 4f

        // --- Raw deficits -------------------------------------------------
        var coldPen = 0f
        var hotPen = 0f
        if (tEff < plateauLo) {
            val c = plateauLo - tEff
            coldPen = 0.10f * c + 0.004f * c * c
        } else if (tEff > plateauHi) {
            val hh = tEff - plateauHi
            hotPen = 0.18f * hh + 0.02f * hh * hh
        }

        val d = max(0f, wEff - (breezyMs + 0.5f))
        var windPen = min(7f, 0.30f * d + 0.15f * d * d)
        // Cold-wind chill: cold air is a clothing problem until the wind
        // drives it through the gear.
        windPen += min(1.5f, 0.05f * max(0f, 12f - h.tempC) * max(0f, wEff - (breezyMs + 1f)))

        var wetPen = 0f
        if (rain) wetPen = 1f + 1.5f * min(1f, h.precipMmH / 2f)
        if (snow) {
            wetPen = 1f + 1.5f * min(1f, h.snowCmH / 2f)
            if (h.tempC <= 1f) wetPen += 1.2f
        }
        if (wet && wEff >= windyMs) wetPen += 1.5f

        val nightPen = if (!h.isDay) 0.6f else 0f

        // --- Preference weighting, per condition group --------------------
        var s = 5f
        s -= comfortDeficit(coldPen, prefs.cold)
        s -= comfortDeficit(hotPen, prefs.hot)
        s -= hazardDeficit(windPen, prefs.wind)
        s -= hazardDeficit(wetPen, if (snow) prefs.snow else prefs.rain)
        s -= comfortDeficit(nightPen, prefs.night)

        // Golden hour: opposite polarity, neutral counts nothing.
        if (h.isGolden) {
            s += when (prefs.golden) {
                Pref.LIKE -> 1.5f
                Pref.NEUTRAL -> 0f
                Pref.DISLIKE -> -1f
            }
        }

        // --- Safety, preference-proof ------------------------------------
        if (rain) s = min(s, 1f)     // wet pavement cap
        if (snow) s = min(s, 0f)
        if (wet && wEff >= windyMs + 3.5f) s = min(s, -5f)
        if (rain && h.tempC <= 1.5f) s = min(s, -4f)   // glaze ice
        if (wEff >= 17f) s = min(s, -4f)               // near-gale gusts

        return Breakdown(
            score = s.coerceIn(-5f, 5f),
            cold = h.tempC < coldC,
            hot = h.tempC > hotC,
            rain = rain,
            snow = snow,
            wind = wEff > breezyMs,
            night = !h.isDay,
            golden = h.isGolden,
        )
    }
}
