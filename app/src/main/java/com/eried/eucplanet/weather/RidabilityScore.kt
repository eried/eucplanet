package com.eried.eucplanet.weather

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
)

/**
 * The ridability score: 10 is a perfect riding hour, 0 is "leave the wheel
 * home". Ten minus a penalty per discomfort, clamped. The four thresholds are
 * the rider's comfort settings; the weights are fixed and express the spec:
 * rain is a bit more dangerous, snow much more, night slightly, wind ramps
 * from noticeable to genuinely hard to ride.
 */
object RidabilityScore {

    /** Score plus which factors bit, so the graph can draw its glyph strip. */
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
        var penalty = 0f

        val cold = h.tempC < coldC
        if (cold) penalty += min(6f, (coldC - h.tempC) * 0.5f)
        val hot = h.tempC > hotC
        if (hot) penalty += min(5f, (h.tempC - hotC) * 0.45f)

        // Snow supersedes rain: providers report snowy hours with both fields
        // populated, and counting the same water twice would double-punish.
        val snow = h.snowCmH > 0f
        val rain = !snow && h.precipMmH > 0f
        if (snow) penalty += min(8f, 5f + h.snowCmH * 1.5f)
        else if (rain) penalty += min(4f, 1.5f + h.precipMmH * 1.0f)

        // Wind: free below breezy, a ramp to 2.5 between the thresholds, then
        // 0.8 per m/s beyond windy - still ridable, but visibly harder.
        val windSpan = (windyMs - breezyMs).coerceAtLeast(0.1f)
        val wind = h.windMs > breezyMs
        if (wind) {
            penalty += if (h.windMs <= windyMs) {
                (h.windMs - breezyMs) / windSpan * 2.5f
            } else {
                min(6f, 2.5f + (h.windMs - windyMs) * 0.8f)
            }
        }

        val night = !h.isDay
        if (night) penalty += 1f

        return Breakdown(
            score = (10f - penalty).coerceIn(0f, 10f),
            cold = cold,
            hot = hot,
            rain = rain,
            snow = snow,
            wind = wind,
            night = night,
        )
    }
}
