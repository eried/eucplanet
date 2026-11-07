package com.eried.eucplanet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import kotlin.concurrent.thread

/**
 * Motor sound generator. Synthesises a virtual engine driven by real-time
 * (speed, pwm) telemetry. Lives as a singleton; [WheelService] feeds telemetry
 * and lifecycle events.
 *
 * The motion model is [EngineDynamics] (pure Kotlin, shared with the offline
 * ride renderer); this class owns the Android side: settings, the AudioTrack
 * and producer thread for the synth, the sampled players, audio focus.
 *
 * Threading:
 *  - Main / coroutine thread calls [applySettings], [pushTelemetry], [start], [stop],
 *    [previewProfile]. These mutate volatile state.
 *  - The audio producer thread reads the volatile params snapshot every buffer fill
 *    and writes PCM to AudioTrack. Allocations are kept off the hot path.
 */
class EngineSoundEngine(
    private val context: Context,
    private val sfxSidecar: SfxSidecar
) {

    private val sampleRate = 44100
    private val bufferFrames = 1024     // ~23ms at 44.1k, small enough for live response
    private val channelMask = AudioFormat.CHANNEL_OUT_MONO

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var producerThread: Thread? = null
    @Volatile private var running: Boolean = false

    @Volatile private var paramsSnapshot: EngineParams = EngineParams.SILENT
    private val synth = EngineSynth(sampleRate)
    private val sampledPlayer = SampledEnginePlayer(context)
    private val compositionPlayer = CompositionEnginePlayer(context)

    // Guards the model: pushTelemetry runs on the service's worker thread (and
    // the preview thread) while the disconnect reset in setConnected runs on
    // the main thread. Never held across start()/stop().
    private val telemetryLock = Any()
    private val model = EngineDynamics()
    private val profile: EngineProfile get() = model.profile

    private var masterVolume: Float = 0.6f
    private var volumeAutoEnabled: Boolean = false
    private var headphonesOnly: Boolean = false
    /** Tracks the previous applySettings()'s enabled flag so we only log toggle edges. */
    private var lastAppliedEnabled: Boolean = false
    private var connected: Boolean = false

    // Audio focus
    private var focusRequest: AudioFocusRequest? = null

    /** Apply settings, called whenever AppSettings changes. */
    fun applySettings(s: AppSettings) {
        val previousProfileKey = profile.key
        val previousEnabled = lastAppliedEnabled
        synchronized(telemetryLock) {
            model.profile = EngineProfile.byKey(s.engineType)
            model.applyCurve(s.engineVolumeAutoCurve)
            model.mufflerKey = s.engineMuffler
            model.gearboxKey = s.engineGearbox
            model.idleBehavior = s.engineIdleBehavior
            model.decelChar = s.engineDecelChar
            model.engineBrakeMode = s.engineBrake
            model.duckMode = s.engineDuckOnVoice
        }
        masterVolume = s.engineVolume.coerceIn(0f, 1f)
        volumeAutoEnabled = s.engineVolumeAutoEnabled
        headphonesOnly = s.engineHeadphonesOnly
        lastAppliedEnabled = s.engineSoundEnabled

        // Service-Mode log: only milestone events (engine selected, sound toggled),
        // never per-tick telemetry. DiagnosticsLogger.info() is a no-op when service
        // mode is off, so this costs nothing in normal operation.
        if (s.engineSoundEnabled && previousProfileKey != profile.key) {
            val pathKind = if (profile.sampleAssetBase != null) "sampled" else profile.kind.name.lowercase()
            DiagnosticsLogger.info("engine: ${profile.key} ($pathKind)")
        }
        if (previousEnabled != s.engineSoundEnabled) {
            DiagnosticsLogger.info(
                if (s.engineSoundEnabled) "engine: sound enabled (${profile.key})"
                else "engine: sound disabled"
            )
        }

        if (!s.engineSoundEnabled) {
            stop()
            return
        }
        // Engine type swap while running: restart the correct path.
        if (running && previousProfileKey != profile.key) {
            stop()
            start()
        }
        // Otherwise start() is gated on connection; called from [setConnected].
    }

    /** Wheel BLE connect/disconnect. */
    fun setConnected(isConnected: Boolean, settings: AppSettings?) {
        connected = isConnected
        if (isConnected && settings?.engineSoundEnabled == true) {
            start()
        } else if (!isConnected) {
            stop()
            // The next wheel session starts clean: across BLE sessions (maybe a
            // different wheel) PWM capability is discovered again.
            synchronized(telemetryLock) { model.resetSession() }
        }
    }

    /** Voice service hook, call when a TTS announcement starts/stops. */
    fun setVoiceActive(active: Boolean) {
        model.voiceActive = active
    }

    /**
     * Tell the engine what the wheel's top speed is so the speed→RPM map matches
     * the wheel. WheelService wires this to WheelRepository.maxSpeedCap.
     */
    fun setMaxSpeedRef(kmh: Float) {
        if (kmh > 5f) model.maxSpeedRefKmh = kmh
    }

    /**
     * Live preview while user is on the settings page (no telemetry yet).
     *
     * Optional overrides let the caller A/B-test a specific knob (volume slider,
     * muffler/gearbox option) without committing the change first. When null,
     * the engine's current state (last [applySettings]) is used.
     *
     * [scenario] picks the motion fed to the engine for the preview duration:
     *  - "DEFAULT" / null, idle → mid-rev → idle sweep (engine type, muffler)
     *  - "GEARBOX": speed sweep through the full band so virtual gears shift
     *  - "DECEL": accel under load then sharp off-throttle to trigger pops
     *  - "BRAKE": sustained coast at speed so engine-brake whine engages
     */
    fun previewProfile(
        key: String,
        durationMs: Long = 2500L,
        volume: Float? = null,
        muffler: String? = null,
        gearbox: String? = null,
        scenario: String = "DEFAULT"
    ) {
        val savedProfile = profile
        val savedVolume = masterVolume
        val savedMuffler = model.mufflerKey
        val savedGearbox = model.gearboxKey
        val savedPwmEverNonZero = model.pwmEverNonZero
        model.profile = EngineProfile.byKey(key)
        if (volume != null) masterVolume = volume.coerceIn(0f, 1f)
        if (muffler != null) model.mufflerKey = muffler
        if (gearbox != null) model.gearboxKey = gearbox
        // Scenario-driven paths use pushTelemetry which only honors pwm-based load once
        // it's seen a non-zero pwm; prime the flag so brake/decel scenarios behave.
        model.pwmEverNonZero = true
        Thread {
            try {
                start()
                val script = EngineRide.preview(scenario, durationMs, model.maxSpeedRefKmh)
                for (step in script) {
                    if (step.direct) pushParamsDirect(step.rpmFraction, step.load)
                    else pushTelemetry(step.speedKmh, step.pwmPercent)
                    Thread.sleep(step.holdMs)
                }
                Thread.sleep(250)
                stop()
            } finally {
                model.profile = savedProfile
                masterVolume = savedVolume
                model.mufflerKey = savedMuffler
                model.gearboxKey = savedGearbox
                model.pwmEverNonZero = savedPwmEverNonZero
                // Reset smoothing state so the next real telemetry tick doesn't carry over
                // the scenario's rpm/load, avoids a transient when the user goes riding right after.
                synchronized(telemetryLock) { model.resetMotion() }
            }
        }.start()
    }

    private fun pushParamsDirect(rpmFraction: Float, load: Float) {
        synchronized(telemetryLock) {
            val now = System.currentTimeMillis()
            emit(model.direct(now, rpmFraction, load), now)
        }
    }

    /** Telemetry tick from WheelService (typically 5-20 Hz). */
    fun pushTelemetry(speedKmh: Float, pwmPercent: Float) {
        synchronized(telemetryLock) {
            val now = System.currentTimeMillis()
            emit(model.tick(now, speedKmh, pwmPercent), now)
        }
    }

    private fun emit(params: EngineParams, now: Long) {
        paramsSnapshot = params
        // Sample-based engine takes its parameters from the same snapshot.
        val rpmNorm = model.rpmNorm(now)
        val sampledVolume = params.masterGain * params.idleAmount
        val pops = params.pendingPops

        if (compositionPlayer.isPlaying()) {
            compositionPlayer.update(rpmNorm, sampledVolume)
            if (params.decelAmount > 0.6f && pops == 0) {
                // Decel transient hasn't been queued as a pop, but the envelope is high
                //, likely a clean throttle-close. Fire the composition's decel one-shot.
                compositionPlayer.fireDecel()
            }
        } else if (sampledPlayer.isPlaying()) {
            sampledPlayer.update(rpmNorm, sampledVolume)
        }
        // Pops on either sampled path go through SoundPool, the synth path consumes them
        // by rendering them into its own buffer, but neither MediaPlayer path has a mix point.
        if (pops > 0 && profile.supportsPops && profile.sampleAssetBase != null) {
            if (profile.popSampleAsset != null) {
                repeat(pops) { sfxSidecar.firePop(profile.popSampleAsset, sampledVolume) }
            }
        }
    }

    /** Start the audio producer thread. Idempotent. */
    fun start() {
        if (running) return
        if (headphonesOnly && !isHeadphonesActive()) {
            Log.i(TAG, "Engine sound suppressed, headphones-only mode and no headphones routed")
            return
        }
        running = true
        requestAudioFocus()
        // Multi-section composition wins over single-asset playback, when a profile
        // declares sampleSections, we use [CompositionEnginePlayer] for proper
        // idle ↔ rev crossfading plus startup/decel/shutdown one-shots.
        if (profile.sampleSections != null) {
            sfxSidecar.load(profile)
            compositionPlayer.start(profile)
            return
        }
        if (profile.sampleAssetBase != null) {
            // Preload pop/brake SFX so the first trigger doesn't get dropped while
            // SoundPool decodes. firePop() drops the call if the asset isn't ready yet,
            // but we want pops to land from the first decel event.
            sfxSidecar.load(profile)
            sampledPlayer.start(profile)
            return
        }
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(bufferFrames * 4)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            producerThread = thread(name = "EngineSoundProducer", isDaemon = true) {
                runProducer()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start engine audio", e)
            running = false
        }
    }

    /** Stop the audio producer thread and release resources. Idempotent. */
    fun stop() {
        running = false
        try {
            producerThread?.join(500)
        } catch (_: InterruptedException) {}
        producerThread = null
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Stop/release threw", e)
            }
        }
        audioTrack = null
        sampledPlayer.stop()
        compositionPlayer.stop()
        sfxSidecar.release()
        abandonAudioFocus()
        synth.reset()
        synchronized(telemetryLock) { model.resetRev() }
    }

    private fun runProducer() {
        val buffer = FloatArray(bufferFrames)
        while (running) {
            val params = paramsSnapshot
            synth.render(params, buffer, 0, bufferFrames)
            val written = audioTrack?.write(buffer, 0, bufferFrames, AudioTrack.WRITE_BLOCKING) ?: 0
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written, stopping")
                running = false
            }
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { /* mix with whatever the system says */ }
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            }
        } catch (_: Throwable) {}
    }

    private fun isHeadphonesActive(): Boolean = AudioOutput.isExternalActive(audioManager)

    companion object {
        private const val TAG = "EngineSoundEngine"
    }
}
