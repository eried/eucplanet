package com.eried.eucplanet.widget

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.weather.HourForecast
import com.eried.eucplanet.weather.WeatherFace
import com.eried.eucplanet.weather.WeatherForecast
import com.eried.eucplanet.weather.WeatherScoring
import kotlin.math.roundToInt

/**
 * What the weather widgets say, for both phones: Android's WeatherWidgets
 * (through WeatherWidgetPublisher) and the iPhone's WidgetKit widgets
 * (IosWeatherWidgets) take their score text, temperature and wind labels
 * here, so an hour reads the same on both.
 */
object WeatherWidgetContent {
    /** From this age the small widget says how old the forecast is rather than the temperature. */
    const val STALE_MS = 3 * WeatherScoring.ONE_HOUR_MS

    /** "+4", "0", "-3": signed because the neutral point is the question. */
    fun signedScore(score: Float): String {
        val v = score.roundToInt()
        return if (v > 0) "+$v" else "$v"
    }

    /** The panel's rule: a window this short asks the provider for 15-minute steps. */
    fun fine(windowHours: Int): Boolean = windowHours <= 12

    /** "18°C", in the rider's unit. */
    fun tempLabel(h: HourForecast, settings: AppSettings): String {
        val unit = Units.effectiveTempUnit(settings)
        return "${Units.temperature(h.tempC, unit).roundToInt()}${Units.tempUnit(unit)}"
    }

    /** "12 km/h"; [speedUnitLabel] is the speed unit in the rider's language (Units.speedUnitRes). */
    fun windLabel(h: HourForecast, settings: AppSettings, speedUnitLabel: String): String {
        val unit = Units.effectiveSpeedUnit(settings)
        return "${Units.speed(h.windMs * 3.6f, unit).roundToInt()} $speedUnitLabel"
    }

    /** One forecast step, scored and worded. */
    data class Point(
        val timeMs: Long,
        val score: Float,
        val scoreLabel: String,
        /** [WeatherFace.key]. */
        val faceKey: String,
        val tempLabel: String,
        val windLabel: String,
    )

    /**
     * Every step of [forecast] from the one in progress when it was fetched,
     * scored with this rider's thresholds. The iPhone's widgets carry them
     * all, since nothing repaints them every hour: the widget itself moves
     * along the steps.
     */
    fun points(forecast: WeatherForecast, settings: AppSettings, speedUnitLabel: String): List<Point> {
        val hours = forecast.hours
        val from = hours.indexOfLast { it.timeMs <= forecast.fetchedAtMs }.coerceAtLeast(0)
        return hours.drop(from).map { h ->
            val b = WeatherScoring.scoreOf(h, settings)
            Point(
                timeMs = h.timeMs,
                score = b.score,
                scoreLabel = signedScore(b.score),
                faceKey = WeatherFace.of(b).key,
                tempLabel = tempLabel(h, settings),
                windLabel = windLabel(h, settings, speedUnitLabel),
            )
        }
    }
}
