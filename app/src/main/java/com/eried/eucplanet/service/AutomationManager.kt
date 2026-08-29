package com.eried.eucplanet.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.audio.AudioOutput
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ApplyWhenIds
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
    private val settingsRepository: SettingsRepository,
    private val legalLockdown: com.eried.eucplanet.data.repository.LegalLockdownController
) {
    companion object {
        private const val TAG = "AutomationManager"
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

    // The rider's baseline volume % (the level before speed-scaling), cached from
    // evaluateVolume so restoreBaselineVolume() can put it back without suspending.
    @Volatile private var cachedBaselinePercent: Int = -1

    // Media control state. mediaAutoPaused = this feature paused playback and may
    // resume it; we never resume media the rider paused themselves. The candidate
    // timestamps implement the "hold past the line" debounce (0 = the condition is
    // not currently met).
    @Volatile private var mediaAutoPaused: Boolean = false
    // The rules themselves live in MediaControlPolicy, where they are tested.
    private var mediaState = MediaControlPolicy.State()
    private var rateState = PlaybackRatePolicy.State()
    private var lightSlowState = HeadlightSlowPolicy.State()

    /** True once the rider has actually ridden since connecting. Keeps the
     *  headlight automation alive while they are stopped; see
     *  [ApplyWhenGate.allowsSticky]. Cleared on disconnect. */
    private var rodeThisSession = false

    /** Last answer from the sun schedule. Cached because that calculation is
     *  the expensive part and only changes across minutes, while the slow
     *  cutoff has to be judged on every telemetry frame. */
    private var lastDarkEnough: Boolean? = null


    /** Proximity lock / unlock decisions. See [ProximityLockEvaluator]. */
    private val proximityLock = ProximityLockEvaluator()

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
        // Legal Mode Lockdown is temporary, so its light button must not leave
        // auto-lights suspended for the rest of the session. Guarded here so
        // every caller is covered, not just the two we know about today.
        if (legalLockdown.isEngaged()) return
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
        lastDarkEnough = null
    }

    /**
     * Called every telemetry tick (~250ms). Evaluates automation rules.
     */
    suspend fun evaluate(settings: AppSettings) {
        // Legal Mode Lockdown stops media control and proximity auto-lock.
        // Auto lights and auto volume keep running: lights are a safety
        // behaviour and the light button is on the locked screen anyway.
        val lockedDown = legalLockdown.isEngaged()
        val connectedNow = wheelRepository.connectionState.value == ConnectionState.CONNECTED
        if (!connectedNow) rodeThisSession = false
        else if (wheelRepository.wheelData.value.speed.absoluteValue >= ApplyWhenGate.RIDING_KMH) {
            rodeThisSession = true
        }
        detectManualLightChange(settings)
        if (!_autoLightsSuspended.value) evaluateLights(settings)
        evaluateVolume(settings)
        val mc = settings.mediaControl
        if (!lockedDown && (mc.pauseEnabled || mc.resumeEnabled)) evaluateMediaControl(settings)
        // Media speed control is parked: it needs notification access the app
        // no longer asks for, and most players ignored the rate anyway. The
        // evaluation below is left intact for whoever revives it.
        // if (!lockedDown) evaluatePlaybackRate(settings)
        // lockEnabled is the whole feature's switch; unlockEnabled is a
        // sub-option of it, and the settings screen only draws the unlock
        // switch while this one is on. Gating on either used to keep the
        // unlock half running invisibly after the rider switched the feature
        // off - they could see no switch for it and had no way to stop it.
        // Resetting on the off path also covers every route that turns it off
        // (settings, the dashboard long-press, restoring a backup).
        if (!lockedDown && settings.proximityLock.lockEnabled) evaluateProximityLock(settings)
        else proximityLock.reset()
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
        // Sticky, not the plain gate: a rider waiting at a crossing has a
        // speed of zero, and the plain "when riding" test switched this
        // automation off at exactly the moment the slow cutoff exists for.
        if (!ApplyWhenGate.allowsSticky(
                mode = settings.lights.applyWhen,
                connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED,
                rodeThisSession = rodeThisSession,
            )
        ) return
        val now = System.currentTimeMillis()

        // Judged on EVERY frame. This used to sit below the check-interval
        // throttle, which is a minute by default, so the policy saw one sample
        // a minute and its three second hold could not be satisfied: the light
        // stayed on all the way to the crossing.
        lightSlowState = HeadlightSlowPolicy.step(
            state = lightSlowState,
            speedKmh = wheelRepository.wheelData.value.speed.absoluteValue,
            thresholdKmh = settings.lights.offBelowKmh,
            nowMs = now,
            enabled = settings.lights.offWhenSlow,
        )

        val location = tripRepository.currentLocation.value
        val interval = if (location == null) settings.autoLightNoGpsRetryMs.toLong()
                       else settings.automationLightCheckIntervalMs.toLong()
        // Only the sun calculation is throttled, and its answer is cached for
        // the frames in between, which is what lets the cutoff act at once.
        if (now - lastLightCheckMs >= interval) {
            lastLightCheckMs = now
            if (location != null) {
                lastDarkEnough = when (
                    val r = SunCalculator.calculateState(
                        location.latitude, location.longitude, TimeZone.getDefault(),
                    )
                ) {
                    is SunCalculator.SunResult.Normal -> {
                        val onAt = r.sunsetMillis - settings.lights.onMinutesBefore * 60_000L
                        val offAt = r.sunriseMillis + settings.lights.offMinutesAfter * 60_000L
                        now >= onAt || now <= offAt
                    }
                    SunCalculator.SunResult.PolarNight -> true
                    SunCalculator.SunResult.MidnightSun -> false
                }
            }
        }

        // Walking pace kills the beam even when the sun says it is wanted, and
        // even when no fix has arrived to ask the sun with: the cutoff needs
        // no schedule. Null is "leave it alone", which only happens when
        // there is no fix AND the rider is still riding.
        val shouldBeOn = HeadlightSlowPolicy.beamOn(
            darkEnough = lastDarkEnough,
            forcedOff = lightSlowState.forcedOff,
        ) ?: return

        val currentLightOn = wheelRepository.wheelData.value.lightOn
        if (shouldBeOn != currentLightOn) {
            val what = if (shouldBeOn) "ON" else "OFF"
            Log.i(TAG, "Auto-lights: turning " + what +
                " (dark=" + lastDarkEnough + ", slowOff=" + lightSlowState.forcedOff + ")")
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
        if (!applyWhenAllows(settings.autoVolumeApplyWhen)) return
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
     * the line for [MediaControlPolicy.HOLD_MS] before we act, so a brief GPS / speed blip
     * is ignored. Resume only ever restarts playback THIS feature paused
     * ([mediaAutoPaused]), so speeding up never starts music the rider deliberately
     * stopped.
     */
    private fun evaluateMediaControl(settings: AppSettings) {
        // Only act on a live wheel connection. A disconnected wheel reports 0 speed,
        // which would otherwise pause your music the moment you walk off with the phone.
        if (wheelRepository.connectionState.value != ConnectionState.CONNECTED) {
            mediaState = mediaState.copy(pauseSince = 0L, resumeSince = 0L)
            return
        }
        val mc = settings.mediaControl
        val step = MediaControlPolicy.step(
            state = mediaState,
            speedKmh = wheelRepository.wheelData.value.speed.absoluteValue,
            nowMs = System.currentTimeMillis(),
            pauseEnabled = mc.pauseEnabled,
            pauseBelowKmh = mc.pauseBelowKmh,
            resumeEnabled = mc.resumeEnabled,
            resumeAboveKmh = mc.resumeAboveKmh,
            // Polled only when a resume is otherwise due: the policy short-
            // circuits on it last, and the poll is the expensive part.
            externalOk = !mc.requireExternalOutput ||
                (mediaState.autoPaused && AudioOutput.isExternalActive(audioManager)),
            connected = true,
        )
        mediaState = step.state
        mediaAutoPaused = step.state.autoPaused
        when (step.action) {
            MediaControlPolicy.Action.PAUSE -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                Log.i(TAG, "Media control: paused (<= ${mc.pauseBelowKmh} km/h)")
            }
            MediaControlPolicy.Action.PLAY -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                Log.i(TAG, "Media control: resumed")
            }
            MediaControlPolicy.Action.NONE -> {}
        }
    }

    /**
     * Dispatch a media-button key (down+up) to whatever app owns the active media
     * session - the same mechanism the Flic / notification action buttons use.
     */
    /**
     * Playback rate follows speed, for players that accept one.
     *
     * Two things can silently make this a no-op and neither is our doing: the
     * rider may not have granted notification access (without it the session
     * list is empty), and the player may not implement a rate at all. Spotify
     * does not; most Media3 apps do. A session that does advertises
     * ACTION_SET_PLAYBACK_SPEED, so only those are touched, and only while
     * they are actually playing - setting a rate on a paused session changes
     * nothing the rider can hear and wakes its UI for no reason.
     */
    /** The shared gate, reading this manager's live wheel state. The decision
     *  itself lives in [ApplyWhenGate] so it can be tested without a wheel. */
    private fun applyWhenAllows(mode: String): Boolean = ApplyWhenGate.allows(
        mode = mode,
        connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED,
        speedKmh = wheelRepository.wheelData.value.speed.absoluteValue,
    )

    private fun evaluatePlaybackRate(settings: AppSettings) {
        if (!applyWhenAllows(settings.mediaControl.rateApplyWhen)) return
        val curve = parseVolumeCurve(settings.mediaControl.rateCurve)
        if (curve.isEmpty()) return
        val speed = wheelRepository.wheelData.value.speed.absoluteValue
        val target = pchipInterpolate(curve, speed)
        val step = PlaybackRatePolicy.step(rateState, target, System.currentTimeMillis())
        val rate = step.rate ?: return
        rateState = step.state
        applyPlaybackRate(rate)
    }

    /** Sends [rate] to every playing session that accepts one; returns how many. */
    private fun applyPlaybackRate(rate: Float): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val mgr = runCatching {
            context.getSystemService(android.media.session.MediaSessionManager::class.java)
        }.getOrNull() ?: return 0
        val listener = android.content.ComponentName(
            context, com.eried.eucplanet.service.MediaAccessService::class.java
        )
        // Throws SecurityException when notification access is not granted,
        // which is the normal state until the rider grants it.
        val sessions = runCatching { mgr.getActiveSessions(listener) }.getOrNull() ?: return 0
        var sent = 0
        for (c in sessions) {
            val st = c.playbackState ?: continue
            if (st.state != android.media.session.PlaybackState.STATE_PLAYING) continue
            // Advertising ACTION_SET_PLAYBACK_SPEED is a manual step the app
            // has to remember, and some that implement the callback never do
            // it. Sending anyway costs nothing - a session that does not
            // handle it simply ignores the call - so the flag is used to
            // explain what happened, not to decide whether to try.
            val advertises = (st.actions and
                android.media.session.PlaybackState.ACTION_SET_PLAYBACK_SPEED) != 0L
            runCatching { c.transportControls.setPlaybackSpeed(rate) }
                .onSuccess {
                    sent++
                    Log.i(
                        TAG,
                        "Media speed: sent ${"%.2f".format(java.util.Locale.US, rate)}x to " +
                            "${c.packageName} (advertised=$advertises, reported=${st.playbackSpeed})",
                    )
                }
                .onFailure { Log.w(TAG, "setPlaybackSpeed failed for ${c.packageName}", it) }
        }
        if (sessions.isNotEmpty() && sent == 0) {
            Log.i(TAG, "Media speed: nothing playing among ${sessions.map { it.packageName }}")
        }
        return sent
    }

    /**
     * Back to normal speed, once.
     *
     * Without this a rider who ends a ride at 1.3x keeps listening at 1.3x
     * for the rest of the day and has to hunt for the control in their
     * player. Called wherever media state is reset.
     */
    fun resetPlaybackRate() {
        if (rateState.lastSent > 0f && rateState.lastSent != PlaybackRatePolicy.NORMAL) {
            applyPlaybackRate(PlaybackRatePolicy.NORMAL)
        }
        rateState = PlaybackRatePolicy.State()
    }

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
        mediaState = MediaControlPolicy.State()
        resetPlaybackRate()
    }

    /**
     * Bluetooth-signal proximity lock / unlock. The decision lives in
     * [ProximityLockEvaluator]; this only supplies the reading and carries out
     * the answer. Locking needs a live link, so it runs while the signal is
     * fading - a full disconnect leaves nothing to send over (documented
     * limitation).
     */
    private fun evaluateProximityLock(settings: AppSettings) {
        if (wheelRepository.connectionState.value != ConnectionState.CONNECTED) return
        val rssi = wheelRepository.wheelData.value.rssiDbm
        when (
            proximityLock.evaluate(
                nowMs = System.currentTimeMillis(),
                rssiDbm = rssi,
                locked = wheelRepository.locked.value,
                settings = settings.proximityLock
            )
        ) {
            ProximityLockEvaluator.Action.LOCK -> {
                wheelRepository.setLock(true)
                Log.i(TAG, "Proximity: locking (rssi=$rssi dBm)")
            }
            ProximityLockEvaluator.Action.UNLOCK -> {
                wheelRepository.setLock(false)
                Log.i(TAG, "Proximity: unlocking (rssi=$rssi dBm)")
            }
            ProximityLockEvaluator.Action.NONE -> Unit
        }
    }

    /** Clear proximity-lock state entirely, for Stop All and for disable. */
    fun resetProximityLock() = proximityLock.reset()

    /**
     * The wheel disconnected. Keeps the arming so a rider who walked out of
     * range and came back still gets the auto-unlock; see
     * [ProximityLockEvaluator.onLinkLost].
     */
    fun onProximityLinkLost() = proximityLock.onLinkLost()
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
