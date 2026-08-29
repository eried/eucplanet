package com.eried.eucplanet.weather

import com.eried.eucplanet.data.model.AppSettings

/**
 * Turning a forecast into scores, the one way.
 *
 * The dashboard did this inline, which was fine while the panel was the only
 * thing that scored an hour. The widgets score in a background worker with no
 * ViewModel anywhere, and a rider comparing the launcher against the app must
 * not see two different numbers for the same hour, so the thresholds and
 * preferences are read from the settings here and nowhere else.
 */
object WeatherScoring {

    /** [RidabilityScore.score] for [h], with this rider's thresholds and prefs. */
    fun scoreOf(h: HourForecast, settings: AppSettings): RidabilityScore.Breakdown {
        val w = settings.weather
        val a = settings.advanced
        return RidabilityScore.score(
            h,
            coldC = a.weatherColdC.toFloat(),
            hotC = a.weatherHotC.toFloat(),
            breezyMs = a.weatherBreezyTenthsMs / 10f,
            windyMs = a.weatherWindyTenthsMs / 10f,
            prefs = RidabilityScore.prefsOf(
                w.prefHot, w.prefCold, w.prefRain, w.prefSnow,
                w.prefWind, w.prefNight, w.prefGolden,
            ),
        )
    }

    /**
     * The slice the rider configured: from now to `windowHours` ahead.
     *
     * Hours already past are dropped, because a widget that has not refreshed
     * since this morning would otherwise open its graph on breakfast. One hour
     * of slack on the near side keeps the hour in progress, which IS now.
     */
    fun window(
        hours: List<HourForecast>,
        windowHours: Int,
        nowMs: Long,
    ): List<HourForecast> {
        if (hours.isEmpty()) return emptyList()
        val from = nowMs - ONE_HOUR_MS
        val to = nowMs + windowHours * ONE_HOUR_MS
        return hours.filter { it.timeMs in from..to }
    }

    /** The forecast entry that covers [nowMs], or the nearest one either side. */
    fun currentOf(hours: List<HourForecast>, nowMs: Long): HourForecast? =
        hours.minByOrNull { kotlin.math.abs(it.timeMs - nowMs) }

    const val ONE_HOUR_MS = 3_600_000L
}
