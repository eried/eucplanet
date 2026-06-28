package com.eried.eucplanet.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.util.SunCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sign

@Singleton
class AutomationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "AutomationManager"
        private const val LIGHT_NO_GPS_RETRY_MS = 2_000L
        // If light state changes within this window after our command, it's us, not the user
        private const val AUTO_TOGGLE_GRACE_MS = 4_000L
        // Below this multiplier we treat manual volume changes as direct baseline edits
        // (avoids divide-by-near-zero amplification when standing still).
        private const val MIN_REBASE_MULTIPLIER = 0.05f
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastLightCheckMs = 0L
    private var lastAutoToggleMs = 0L
    private var lastKnownLightOn: Boolean? = null

    // Track the system music volume we last wrote so we can distinguish our own writes
    // from a user moving the slider. -1 = we have not written anything yet this session.
    private var lastWrittenSystemVol: Int = -1

    private val _autoLightsSuspended = MutableStateFlow(false)
    val autoLightsSuspended: StateFlow<Boolean> = _autoLightsSuspended.asStateFlow()

    /** Called from the UI / Flic paths whenever the user toggles the light manually. */
    fun notifyManualLightChange() {
        if (!_autoLightsSuspended.value) {
            Log.i(TAG, "Auto-lights suspended for this session (manual change)")
        }
        _autoLightsSuspended.value = true
    }

    /** Called on wheel reconnect or when the auto-lights setting is toggled. */
    fun clearLightsSuspension() {
        if (_autoLightsSuspended.value) {
            Log.i(TAG, "Auto-lights suspension cleared")
        }
        _autoLightsSuspended.value = false
        lastKnownLightOn = null
    }

    /** Reset the throttle so the next tick re-evaluates immediately. */
    fun triggerImmediateLightEvaluation() {
        lastLightCheckMs = 0L
    }

    /**
     * Called every telemetry tick (~250ms). Evaluates automation rules.
     */
    suspend fun evaluate(settings: AppSettings) {
        detectManualLightChange()
        if (settings.autoLightsEnabled && !_autoLightsSuspended.value) evaluateLights(settings)
        if (settings.autoVolumeEnabled) evaluateVolume(settings)
    }

    /** Watch telemetry: if the wheel's light state flips without a recent auto-toggle, it's a manual change. */
    private fun detectManualLightChange() {
        val current = wheelRepository.wheelData.value.lightOn
        val previous = lastKnownLightOn
        lastKnownLightOn = current
        if (previous == null || previous == current) return
        if (lastAutoToggleMs == 0L) return
        val sinceAutoToggle = System.currentTimeMillis() - lastAutoToggleMs
        if (sinceAutoToggle > AUTO_TOGGLE_GRACE_MS) {
            notifyManualLightChange()
        }
    }

    private fun evaluateLights(settings: AppSettings) {
        val now = System.currentTimeMillis()
        val location = tripRepository.currentLocation.value
        val interval = if (location == null) LIGHT_NO_GPS_RETRY_MS
                       else settings.automationLightCheckIntervalMs.toLong()
        if (now - lastLightCheckMs < interval) return
        lastLightCheckMs = now

        if (location == null) return  // will retry in 2s

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
        if (shouldBeOn != currentLightOn) {
            Log.i(TAG, "Auto-lights: turning ${if (shouldBeOn) "ON" else "OFF"} (lat=$lat, lon=$lon)")
            wheelRepository.toggleLight()
            lastAutoToggleMs = now
        }
    }

    /**
     * Speed-based volume control. Multiplier curve maps speed → 0×–2×, applied to a remembered
     * "baseline" volume the user picks naturally. Manual slider movements rebase the baseline
     * so the user-visible volume is always exactly what they set, while the curve continues to
     * track speed from there.
     */
    private suspend fun evaluateVolume(settings: AppSettings) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return

        val systemVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val systemPercent = (systemVol * 100f / maxVol)

        val curve = parseVolumeCurve(settings.autoVolumeCurve)
        val speed = wheelRepository.wheelData.value.speed.absoluteValue
        val multiplier = pchipInterpolate(curve, speed)

        // Initialize baseline from the user's current volume on first tick after enable.
        var baseline = settings.autoVolumeBaselinePercent
        if (baseline < 0) {
            baseline = systemPercent.roundToInt().coerceIn(0, 100)
            settingsRepository.update(settings.copy(autoVolumeBaselinePercent = baseline))
            lastWrittenSystemVol = systemVol
            return
        }

        // Manual change detection: if the system volume isn't what we last wrote, the user moved
        // the slider since our previous tick. Rebase the baseline.
        if (lastWrittenSystemVol != -1 && systemVol != lastWrittenSystemVol) {
            val newBaseline = if (multiplier >= MIN_REBASE_MULTIPLIER) {
                (systemPercent / multiplier).roundToInt().coerceIn(0, 100)
            } else {
                // Near-zero multiplier (typically standstill): treat manual change as a direct
                // baseline edit. Dividing by ~0 would explode.
                systemPercent.roundToInt().coerceIn(0, 100)
            }
            if (newBaseline != baseline) {
                Log.i(TAG, "Auto-volume baseline rebased: $baseline% -> $newBaseline% " +
                    "(manual=${systemPercent.roundToInt()}%, multiplier=${"%.2f".format(multiplier)})")
                baseline = newBaseline
                settingsRepository.update(settings.copy(autoVolumeBaselinePercent = baseline))
            }
        }

        val targetPercent = (baseline * multiplier).coerceIn(0f, 100f)
        val targetVol = (maxVol * targetPercent / 100f).roundToInt().coerceIn(0, maxVol)

        if (targetVol != systemVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            lastWrittenSystemVol = targetVol
        } else {
            // No write needed but still record what the system shows so a non-write tick
            // doesn't get misread as a manual change next time.
            lastWrittenSystemVol = systemVol
        }
    }
}

/** Curve format: "speed:multiplier,speed:multiplier,..." e.g. "0:0,25:0.6,50:1.2,75:2.0". */
fun parseVolumeCurve(raw: String): List<Pair<Float, Float>> {
    return raw.split(",").mapNotNull { pair ->
        val parts = pair.trim().split(":")
        if (parts.size == 2) {
            val speed = parts[0].toFloatOrNull()
            val mult = parts[1].toFloatOrNull()
            if (speed != null && mult != null) speed to mult else null
        } else null
    }.sortedBy { it.first }
}

fun encodeVolumeCurve(points: List<Pair<Float, Float>>): String {
    return points.sortedBy { it.first }
        .joinToString(",") { (s, m) -> "${s.roundToInt()}:${"%.2f".format(java.util.Locale.US, m)}" }
}

/**
 * PCHIP (Piecewise Cubic Hermite Interpolating Polynomial, Fritsch–Carlson 1980).
 * Smooth like a spline but never overshoots between monotonic control points.
 * Returns multiplier value at the given speed; clamps to [0, 2].
 */
fun pchipInterpolate(points: List<Pair<Float, Float>>, x: Float): Float {
    if (points.isEmpty()) return 0f
    if (points.size == 1) return points[0].second.coerceIn(0f, 2f)
    val sorted = points.sortedBy { it.first }
    if (x <= sorted.first().first) return sorted.first().second.coerceIn(0f, 2f)
    if (x >= sorted.last().first) return sorted.last().second.coerceIn(0f, 2f)

    val n = sorted.size
    // Slopes of each segment
    val h = FloatArray(n - 1)
    val d = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        h[i] = sorted[i + 1].first - sorted[i].first
        d[i] = (sorted[i + 1].second - sorted[i].second) / h[i]
    }

    // Tangents at each control point (Fritsch–Carlson)
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1].sign != d[i].sign || d[i - 1] == 0f || d[i] == 0f) {
            0f
        } else {
            (d[i - 1] + d[i]) / 2f
        }
    }
    // Monotonicity-preserving tangent clipping
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    // Find segment
    var k = 0
    for (i in 0 until n - 1) {
        if (x >= sorted[i].first && x <= sorted[i + 1].first) {
            k = i
            break
        }
    }
    val hk = h[k]
    val t = (x - sorted[k].first) / hk
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2
    val y = h00 * sorted[k].second +
            h10 * hk * m[k] +
            h01 * sorted[k + 1].second +
            h11 * hk * m[k + 1]
    return y.coerceIn(0f, 2f)
}
