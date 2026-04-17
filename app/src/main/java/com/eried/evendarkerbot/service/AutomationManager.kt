package com.eried.evendarkerbot.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.eried.evendarkerbot.data.model.AppSettings
import com.eried.evendarkerbot.data.repository.TripRepository
import com.eried.evendarkerbot.data.repository.WheelRepository
import com.eried.evendarkerbot.util.SunCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Singleton
class AutomationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val tripRepository: TripRepository
) {
    companion object {
        private const val TAG = "AutomationManager"
        private const val LIGHT_CHECK_INTERVAL_MS = 60_000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastLightCheckMs = 0L
    private var lastLightState: Boolean? = null

    /**
     * Called every telemetry tick (~250ms). Evaluates automation rules.
     */
    fun evaluate(settings: AppSettings) {
        if (settings.autoLightsEnabled) evaluateLights(settings)
        if (settings.autoVolumeEnabled) evaluateVolume(settings)
    }

    private fun evaluateLights(settings: AppSettings) {
        val now = System.currentTimeMillis()
        if (now - lastLightCheckMs < LIGHT_CHECK_INTERVAL_MS) return
        lastLightCheckMs = now

        val location = tripRepository.currentLocation.value ?: return
        val lat = location.latitude
        val lon = location.longitude

        val shouldBeOn = when (val r = SunCalculator.calculateState(lat, lon, TimeZone.getDefault())) {
            is SunCalculator.SunResult.Normal -> {
                val lightsOnTime = r.sunsetMillis - settings.autoLightsOnMinutesBefore * 60_000L
                val lightsOffTime = r.sunriseMillis + settings.autoLightsOffMinutesAfter * 60_000L
                now >= lightsOnTime || now <= lightsOffTime
            }
            SunCalculator.SunResult.PolarNight -> true
            SunCalculator.SunResult.MidnightSun -> false
        }

        val currentLightOn = wheelRepository.wheelData.value.lightOn
        if (shouldBeOn != lastLightState && shouldBeOn != currentLightOn) {
            Log.i(TAG, "Auto-lights: turning ${if (shouldBeOn) "ON" else "OFF"} (lat=$lat, lon=$lon)")
            wheelRepository.toggleLight()
            lastLightState = shouldBeOn
        } else {
            lastLightState = shouldBeOn
        }
    }

    private fun evaluateVolume(settings: AppSettings) {
        val speed = wheelRepository.wheelData.value.speed.absoluteValue
        val curve = parseVolumeCurve(settings.autoVolumeCurve)
        if (curve.size < 2) return

        val volumePercent = interpolate(curve, speed)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = (maxVol * volumePercent / 100f).roundToInt().coerceIn(0, maxVol)

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (targetVol != currentVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }
    }

    private fun interpolate(points: List<Pair<Float, Float>>, speed: Float): Float {
        if (points.size < 2) return points.firstOrNull()?.second ?: 0f
        if (speed <= points.first().first) return points.first().second
        if (speed >= points.last().first) return points.last().second

        // Find the segment
        var segIdx = 0
        for (i in 0 until points.size - 1) {
            if (speed >= points[i].first && speed <= points[i + 1].first) {
                segIdx = i
                break
            }
        }

        // Catmull-Rom spline interpolation
        val p0 = if (segIdx > 0) points[segIdx - 1] else {
            val (s1, v1) = points[0]
            val (s2, v2) = points[1]
            (s1 - (s2 - s1)) to (v1 - (v2 - v1))
        }
        val p1 = points[segIdx]
        val p2 = points[segIdx + 1]
        val p3 = if (segIdx + 2 < points.size) points[segIdx + 2] else {
            val (s1, v1) = points[points.size - 2]
            val (s2, v2) = points[points.size - 1]
            (s2 + (s2 - s1)) to (v2 + (v2 - v1))
        }

        val t = if (p2.first != p1.first) {
            (speed - p1.first) / (p2.first - p1.first)
        } else 0f
        val t2 = t * t
        val t3 = t2 * t

        val v = 0.5f * (
            (2f * p1.second) +
            (-p0.second + p2.second) * t +
            (2f * p0.second - 5f * p1.second + 4f * p2.second - p3.second) * t2 +
            (-p0.second + 3f * p1.second - 3f * p2.second + p3.second) * t3
        )

        return v.coerceIn(0f, 100f)
    }
}

fun parseVolumeCurve(raw: String): List<Pair<Float, Float>> {
    return raw.split(",").mapNotNull { pair ->
        val parts = pair.trim().split(":")
        if (parts.size == 2) {
            val speed = parts[0].toFloatOrNull()
            val vol = parts[1].toFloatOrNull()
            if (speed != null && vol != null) speed to vol else null
        } else null
    }.sortedBy { it.first }
}

fun encodeVolumeCurve(points: List<Pair<Float, Float>>): String {
    return points.sortedBy { it.first }
        .joinToString(",") { "${it.first.roundToInt()}:${it.second.roundToInt()}" }
}
