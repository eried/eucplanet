package com.eried.eucplanet.widget

import android.content.Context
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.weather.WeatherFace
import com.eried.eucplanet.weather.WeatherForecast
import com.eried.eucplanet.weather.WeatherScoring
import kotlin.math.roundToInt

/**
 * Turns a forecast into what the home screen widgets draw.
 *
 * Two callers, one implementation, on purpose. [WeatherWidgetWorker] publishes
 * after its own background fetch; the dashboard publishes whenever the rider
 * opens the panel, which is what keeps a widget useful on a phone that never
 * grants background location. If these built the snapshot separately, the
 * launcher and the app could show different faces for the same hour.
 */
object WeatherWidgetPublisher {

    fun publish(
        context: Context,
        forecast: WeatherForecast,
        settings: AppSettings,
        place: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        // Nothing placed: skip the work rather than write a file nobody reads.
        if (!WeatherWidgetBase.anyPlaced(context)) return
        WeatherSnapshot.save(context, build(context, forecast, settings, place, nowMs))
        WeatherWidgetBase.renderAll(context)
    }

    fun build(
        context: Context,
        forecast: WeatherForecast,
        settings: AppSettings,
        place: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): WeatherSnapshot {
        val w = settings.weather
        val window = WeatherScoring.window(forecast.hours, w.windowHours, nowMs)
        val scored = window.map { it to WeatherScoring.scoreOf(it, settings) }
        val currentHour = WeatherScoring.currentOf(window, nowMs)
        val current = currentHour?.let { WeatherScoring.scoreOf(it, settings) }
        val tempUnit = Units.effectiveTempUnit(settings)
        val speedUnit = Units.effectiveSpeedUnit(settings)
        return WeatherSnapshot(
            fetchedAtMs = forecast.fetchedAtMs,
            score = current?.score ?: 0f,
            faceKey = current?.let { WeatherFace.of(it).key } ?: "MEH",
            place = place.orEmpty(),
            tempLabel = currentHour?.let {
                "${Units.temperature(it.tempC, tempUnit).roundToInt()}${Units.tempUnit(tempUnit)}"
            }.orEmpty(),
            windLabel = currentHour?.let {
                "${Units.speed(it.windMs * 3.6f, speedUnit).roundToInt()} " +
                    Units.speedUnit(context, speedUnit)
            }.orEmpty(),
            windowHours = w.windowHours,
            series = scored.map { it.second.score },
            seriesStartMs = scored.firstOrNull()?.first?.timeMs ?: 0L,
            // Even spacing is assumed by the graph. The provider gives hourly
            // or 15-minute steps, both even, so the gap between the first two
            // describes the rest.
            seriesStepMs = if (scored.size >= 2)
                scored[1].first.timeMs - scored[0].first.timeMs else 0L,
            faces = scored.map { WeatherFace.of(it.second).key },
            failed = false,
            lat = forecast.lat,
            lon = forecast.lon,
        )
    }
}
