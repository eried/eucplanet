package com.eried.eucplanet.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.ble.ConnectionState
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
        // Below this multiplier we treat manual volume changes as direct baseline edits
        // (avoids divide-by-near-zero amplification when standing still).
        private const val MIN_REBASE_MULTIPLIER = 0.05f
        // Media control: the speed condition must hold this long before we act, so a
        // momentary GPS / speed blip doesn't pause or resume playback.
        private const val MEDIA_CONTROL_HOLD_MS = 3000L
        // Minimum dead-band (km/h) enforced between the pause and resume speeds even
        // if the rider set them equal - guarantees the anti-thrash gap always exists.
        private const val MEDIA_CONTROL_MIN_GAP_KMH = 2
        // Proximity lock: RSSI must hold past the threshold this long before we act.
        // Kept equal to MEDIA_CONTROL_HOLD_MS so both automations feel consistent; the
        // dead-band gap is what actually prevents flip-flop between lock and unlock.
        private const val PROX_HOLD_MS = 3000L
        // Minimum dead-band (dBm) kept between the lock and unlock thresholds. Small,
        // so the two can sit close for a snappy transition; the hold above soaks up
        // the RSSI jitter that such a tight gap would otherwise let flip-flop.
        private const val PROX_MIN_GAP_DBM = 2
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastLightCheckMs = 0L
    private var lastAutoToggleMs = 0L
    private var lastKnownLightOn: Boolean? = null

    // Track the system music volume we last wrote so we can distinguish our own writes
    // from a user moving the slider. -1 = we have not written anything yet this session.
    private var lastWrittenSystemVol: Int = -1

    // The rider's baseline volume % (the level before speed-scaling), cached from
    // evaluateVolume so restoreBaselineVolume() can put it back without suspending.
    @Volatile private var cachedBaselinePercent: Int = -1

    // Media control state. mediaAutoPaused = this feature paused playback and may
    // resume it; we never resume media the rider paused themselves. The candidate
    // timestamps implement the "hold past the line" debounce (0 = the condition is
    // not currently met).
    @Volatile private var mediaAutoPaused: Boolean = false
    private var mediaPauseCandidateSinceMs: Long = 0L
    private var mediaResumeCandidateSinceMs: Long = 0L

    // Proximity-lock "hold past the line" timestamps (0 = condition not currently met).
    private var proxLockCandidateSinceMs: Long = 0L
    private var proxUnlockCandidateSinceMs: Long = 0L

    /**
     * Put the media volume back to the rider's baseline - the level it was at
     * before speed-based auto-volume started scaling it down. Called on
     * disconnect / Stop All / when the feature is disabled, so the app never
     * leaves STREAM_MUSIC turned down after the rider stops (they were having to
     * manually raise it every time). No-op if we never touched the volume.
     */
    fun restoreBaselineVolume() {
        if (lastWrittenSystemVol == -1) return
        lastWrittenSystemVol = -1            // stop tracking; next enable re-initialises
        val baseline = cachedBaselinePercent
        if (baseline < 0) return
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return
        val target = (maxVol * baseline / 100f).roundToInt().coerceIn(0, maxVol)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        Log.i(TAG, "Restored media volume to baseline $baseline% ($target/$maxVol)")
    }

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
        detectManualLightChange(settings)
        if (settings.autoLightsEnabled && !_autoLightsSuspended.value) evaluateLights(settings)
        if (settings.autoVolumeEnabled) evaluateVolume(settings)
        val mc = settings.mediaControl
        if (mc.pauseEnabled || mc.resumeEnabled) evaluateMediaControl(settings)
        val pl = settings.proximityLock
        if (pl.lockEnabled || pl.unlockEnabled) evaluateProximityLock(settings)
    }

    /** Watch telemetry: if the wheel's light state flips without a recent auto-toggle, it's a manual change. */
    private fun detectManualLightChange(settings: AppSettings) {
        val current = wheelRepository.wheelData.value.lightOn
        val previous = lastKnownLightOn
        lastKnownLightOn = current
        if (previous == null || previous == current) return
        if (lastAutoToggleMs == 0L) return
        val sinceAutoToggle = System.currentTimeMillis() - lastAutoToggleMs
        if (sinceAutoToggle > settings.autoToggleGraceMs.toLong()) {
            notifyManualLightChange()
        }
    }

    private fun evaluateLights(settings: AppSettings) {
        val now = System.currentTimeMillis()
        val location = tripRepository.currentLocation.value
        val interval = if (location == null) settings.autoLightNoGpsRetryMs.toLong()
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
     * Speed-based volume control. BOOST-ONLY: the multiplier is clamped to >= 1×
     * so it can raise the media volume as speed climbs but never pull it BELOW
     * the rider's own baseline (a curve dipping under 1× used to drag the volume
     * toward zero at standstill and leave it there - not what auto-volume is for).
     * Applied to a remembered baseline the rider picks naturally; manual slider
     * movements rebase the baseline so what they set is always the floor.
     */
    private suspend fun evaluateVolume(settings: AppSettings) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return

        val systemVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val systemPercent = (systemVol * 100f / maxVol)

        val curve = parseVolumeCurve(settings.autoVolumeCurve)
        val speed = wheelRepository.wheelData.value.speed.absoluteValue
        // Boost-only: never below 1x, so auto-volume can only raise the rider's
        // baseline with speed, never lower it (that was the volume-sinking bug).
        val multiplier = pchipInterpolate(curve, speed).coerceAtLeast(1f)

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

        // Remember the baseline so restoreBaselineVolume() can put it back on
        // disconnect / Stop All / disable (see that method).
        cachedBaselinePercent = baseline

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

    /**
     * Speed-based media pause / resume. Pauses playback when the rider slows to or
     * below [MediaControlSettings.pauseBelowKmh] and resumes it when they speed up to
     * or above [MediaControlSettings.resumeAboveKmh]. The gap between the two is a
     * dead-band that prevents rapid flipping; on top of that the speed must hold past
     * the line for [MEDIA_CONTROL_HOLD_MS] before we act, so a brief GPS / speed blip
     * is ignored. Resume only ever restarts playback THIS feature paused
     * ([mediaAutoPaused]), so speeding up never starts music the rider deliberately
     * stopped.
     */
    private fun evaluateMediaControl(settings: AppSettings) {
        // Only act on a live wheel connection. A disconnected wheel reports 0 speed,
        // which would otherwise pause your music the moment you walk off with the phone.
        if (wheelRepository.connectionState.value != ConnectionState.CONNECTED) {
            mediaPauseCandidateSinceMs = 0L
            mediaResumeCandidateSinceMs = 0L
            return
        }
        val mc = settings.mediaControl
        val speed = wheelRepository.wheelData.value.speed.absoluteValue
        val now = System.currentTimeMillis()
        val pauseAt = mc.pauseBelowKmh
        // Always keep the resume threshold above the pause one so a dead-band exists.
        val resumeAt = maxOf(mc.resumeAboveKmh, pauseAt + MEDIA_CONTROL_MIN_GAP_KMH)

        // Pause when slow (only if we haven't already auto-paused).
        if (mc.pauseEnabled && !mediaAutoPaused && speed <= pauseAt) {
            if (mediaPauseCandidateSinceMs == 0L) mediaPauseCandidateSinceMs = now
            if (now - mediaPauseCandidateSinceMs >= MEDIA_CONTROL_HOLD_MS) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                mediaAutoPaused = true
                mediaPauseCandidateSinceMs = 0L
                Log.i(TAG, "Media control: paused (speed=${speed.roundToInt()} <= $pauseAt km/h)")
            }
        } else {
            mediaPauseCandidateSinceMs = 0L
        }

        // Resume when moving again - only what we paused.
        if (mc.resumeEnabled && mediaAutoPaused && speed >= resumeAt) {
            if (mediaResumeCandidateSinceMs == 0L) mediaResumeCandidateSinceMs = now
            if (now - mediaResumeCandidateSinceMs >= MEDIA_CONTROL_HOLD_MS) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                mediaAutoPaused = false
                mediaResumeCandidateSinceMs = 0L
                Log.i(TAG, "Media control: resumed (speed=${speed.roundToInt()} >= $resumeAt km/h)")
            }
        } else {
            mediaResumeCandidateSinceMs = 0L
        }
    }

    /**
     * Dispatch a media-button key (down+up) to whatever app owns the active media
     * session - the same mechanism the Flic / notification action buttons use.
     */
    private fun sendMediaKey(keyCode: Int) {
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Log.w(TAG, "sendMediaKey($keyCode) failed", it) }
    }

    /**
     * Clear media-control state on disconnect / Stop All / disable. We deliberately
     * do NOT auto-resume here - playback is left wherever it is (the rider can press
     * play) rather than risk blasting audio when a ride ends.
     */
    fun resetMediaControl() {
        mediaAutoPaused = false
        mediaPauseCandidateSinceMs = 0L
        mediaResumeCandidateSinceMs = 0L
    }

    /**
     * Bluetooth-signal proximity lock / unlock. While connected, locks the wheel
     * when the RSSI drops to/below [ProximityLockSettings.lockBelowDbm] (the rider
     * is walking away, still just in range) and unlocks it when the RSSI rises
     * to/above [ProximityLockSettings.unlockAboveDbm] (the rider is back close).
     * The gap between the two is a dead-band; the reading must also hold past the
     * line for [PROX_HOLD_MS] before acting, because RSSI is noisy. Only ever locks
     * an unlocked wheel and unlocks a locked one, so it can't fight the rider or
     * itself. Locking needs a live link, so this runs while the signal is fading -
     * a full disconnect leaves nothing to send over (documented limitation).
     */
    private fun evaluateProximityLock(settings: AppSettings) {
        if (wheelRepository.connectionState.value != ConnectionState.CONNECTED) return
        val pl = settings.proximityLock
        val rssi = wheelRepository.wheelData.value.rssiDbm
        if (rssi == 0) return  // no RSSI reading yet
        val locked = wheelRepository.locked.value
        val now = System.currentTimeMillis()
        val lockAt = pl.lockBelowDbm
        // Unlock threshold always kept stronger than lock, so a dead-band exists.
        val unlockAt = maxOf(pl.unlockAboveDbm, lockAt + PROX_MIN_GAP_DBM)

        // Walking away: weak signal, still connected, currently unlocked -> lock.
        if (pl.lockEnabled && !locked && rssi <= lockAt) {
            if (proxLockCandidateSinceMs == 0L) proxLockCandidateSinceMs = now
            if (now - proxLockCandidateSinceMs >= PROX_HOLD_MS) {
                wheelRepository.setLock(true)
                proxLockCandidateSinceMs = 0L
                Log.i(TAG, "Proximity: locking (rssi=$rssi <= $lockAt dBm)")
            }
        } else proxLockCandidateSinceMs = 0L

        // Returning: strong signal, currently locked -> unlock.
        if (pl.unlockEnabled && locked && rssi >= unlockAt) {
            if (proxUnlockCandidateSinceMs == 0L) proxUnlockCandidateSinceMs = now
            if (now - proxUnlockCandidateSinceMs >= PROX_HOLD_MS) {
                wheelRepository.setLock(false)
                proxUnlockCandidateSinceMs = 0L
                Log.i(TAG, "Proximity: unlocking (rssi=$rssi >= $unlockAt dBm)")
            }
        } else proxUnlockCandidateSinceMs = 0L
    }

    /** Clear proximity-lock hold state on disconnect / Stop All / disable. */
    fun resetProximityLock() {
        proxLockCandidateSinceMs = 0L
        proxUnlockCandidateSinceMs = 0L
    }
}

/**
 * Ceiling for the auto-volume boost multiplier. Single source of truth shared by the
 * curve editor's Y axis and [pchipInterpolate]'s output clamp, so the draggable range
 * and the applied boost can never drift apart.
 */
const val AUTO_VOLUME_MAX_MULTIPLIER = 3f

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
 * Returns multiplier value at the given speed; clamps to [0, AUTO_VOLUME_MAX_MULTIPLIER].
 */
fun pchipInterpolate(points: List<Pair<Float, Float>>, x: Float): Float {
    if (points.isEmpty()) return 0f
    if (points.size == 1) return points[0].second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
    val sorted = points.sortedBy { it.first }
    if (x <= sorted.first().first) return sorted.first().second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
    if (x >= sorted.last().first) return sorted.last().second.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)

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
    return y.coerceIn(0f, AUTO_VOLUME_MAX_MULTIPLIER)
}
