package com.eried.eucplanet.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.audio.AudioOutput
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * The Android half of the shared [AutomationManager]: the media volume by
 * speed and media pause / resume by speed, over AudioManager. The rest of the
 * automations (headlight, beeper, proximity lock) are shared and run on both
 * phones; iOS gives an app no way to drive other apps' audio.
 *
 * Each step here is synchronized: [evaluateMediaControl] runs on the telemetry
 * collector's worker thread while the resets arrive from the main thread
 * (disconnect, Stop All, Settings) and from the Flic callback thread.
 */
class AndroidMediaAutomation(
    private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
) : MediaAutomation {
    companion object {
        private const val TAG = "AutomationManager"
        // Below this multiplier we treat manual volume changes as direct baseline edits
        // (avoids divide-by-near-zero amplification when standing still).
        private const val MIN_REBASE_MULTIPLIER = 0.05f
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    /**
     * Put the media volume back to the rider's baseline - the level it was at
     * before speed-based auto-volume started scaling it down. Called on
     * disconnect / Stop All / when the feature is disabled, so the app never
     * leaves STREAM_MUSIC turned down after the rider stops (they were having to
     * manually raise it every time). No-op if we never touched the volume.
     */
    @Synchronized
    override fun restoreBaselineVolume() {
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

    /**
     * Speed-based volume control. BOOST-ONLY: the multiplier is clamped to >= 1×
     * so it can raise the media volume as speed climbs but never pull it BELOW
     * the rider's own baseline (a curve dipping under 1× used to drag the volume
     * toward zero at standstill and leave it there - not what auto-volume is for).
     * Applied to a remembered baseline the rider picks naturally; manual slider
     * movements rebase the baseline so what they set is always the floor.
     */
    override suspend fun evaluateVolume(settings: AppSettings) {
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
    @Synchronized
    override fun evaluateMediaControl(settings: AppSettings) {
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

    /** The shared gate, reading this manager's live wheel state. The decision
     *  itself lives in [ApplyWhenGate] so it can be tested without a wheel. */
    private fun applyWhenAllows(mode: String): Boolean = ApplyWhenGate.allows(
        mode = mode,
        connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED,
        speedKmh = wheelRepository.wheelData.value.speed.absoluteValue,
    )

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
     *
     * Parked: nothing calls it while media speed control is switched off (see
     * AutomationsContent). Left intact for whoever revives it.
     */
    @Suppress("unused")
    fun evaluatePlaybackRate(settings: AppSettings) {
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
    @Synchronized
    override fun resetMediaControl() {
        mediaAutoPaused = false
        mediaState = MediaControlPolicy.State()
        resetPlaybackRate()
    }
}
