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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motor sound generator. Synthesises a virtual engine driven by real-time
 * (speed, pwm) telemetry. Lives as a singleton; [WheelService] feeds telemetry
 * and lifecycle events.
 *
 * Threading:
 *  - Main / coroutine thread calls [applySettings], [pushTelemetry], [start], [stop],
 *    [previewProfile]. These mutate volatile state.
 *  - The audio producer thread reads the volatile params snapshot every buffer fill
 *    and writes PCM to AudioTrack. Allocations are kept off the hot path.
 *
 * The engine is otherwise stateless from the caller's perspective, just feed it
 * telemetry and tweak its [AppSettings] block.
 */
@Singleton
class EngineSoundEngine @Inject constructor(
    @ApplicationContext private val context: Context,
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

    /**
     * Reference top speed used to map km/h → rev band. Updated from
     * WheelRepository.maxSpeedCap so a 50 km/h wheel revs out at 50 km/h and
     * a 150 km/h wheel takes longer to hit redline.
     */
    @Volatile private var maxSpeedRefKmh: Float = 90f

    // --- Main-thread state used to derive params from raw telemetry ---
    private var profile: EngineProfile = EngineProfile.byKey("FOUR_STROKE_SINGLE")
    private var masterVolume: Float = 0.6f
    private var volumeAutoEnabled: Boolean = false
    /** Parsed 4-point curve cached from AppSettings. Re-evaluated each pushTelemetry. */
    private var volumeAutoCurve: List<Pair<Float, Float>> = emptyList()
    /** Smoothed speed-based multiplier so abrupt speed changes don't click the gain. */
    @Volatile private var smoothedSpeedMult: Float = 1f
    private var mufflerKey: String = "HALF"
    private var gearboxKey: String = "FOUR"
    private var idleBehavior: String = "FADE"
    private var decelChar: String = "STANDARD"
    private var engineBrakeMode: String = "LIGHT"
    private var duckMode: String = "DUCK"
    private var headphonesOnly: Boolean = false

    private var smoothedRpm: Float = 0f
    private var smoothedLoad: Float = 0f
    private var lastPwm: Float = 0f
    private var pwmEverNonZero: Boolean = false      // becomes true once we see real PWM, so we don't fall back forever
    private var lastSpeedKmh: Float = 0f
    private var lastMovingAtMs: Long = 0L
    private var lastTelemetryAtMs: Long = 0L
    private var idleEnvelope: Float = 0f             // 0 = silent, 1 = full idle
    private var decelEnvelope: Float = 0f
    private var brakeEnvelope: Float = 0f            // 0..1, engine-brake whine envelope (slow attack, slow release)
    private var pendingPops: Int = 0
    private var voiceActive: Boolean = false
    private var voiceDuckGain: Float = 1f
    /** Tracks the previous applySettings()'s enabled flag so we only log toggle edges. */
    private var lastAppliedEnabled: Boolean = false

    private val revDetector = RevDetector()
    private var connected: Boolean = false

    /** Last-built synthetic custom profile, refreshed on every applySettings. */
    private var customProfile: EngineProfile =
        com.eried.eucplanet.audio.CustomEngineSounds.buildProfile(emptyMap(), modulatePitch = true)

    private fun resolveProfile(key: String): EngineProfile =
        if (key == EngineProfile.CUSTOM_KEY) customProfile else EngineProfile.byKey(key)

    // Audio focus
    private var focusRequest: AudioFocusRequest? = null

    /** Apply settings, called whenever AppSettings changes. */
    fun applySettings(s: AppSettings) {
        val previousProfileKey = profile.key
        val previousEnabled = lastAppliedEnabled
        customProfile = com.eried.eucplanet.audio.CustomEngineSounds.buildProfile(
            slots = com.eried.eucplanet.audio.CustomEngineSounds.parseSlots(s.engineCustomSounds),
            modulatePitch = s.engineCustomModulatePitch,
        )
        profile = resolveProfile(s.engineType)
        masterVolume = s.engineVolume.coerceIn(0f, 1f)
        volumeAutoEnabled = s.engineVolumeAutoEnabled
        volumeAutoCurve = com.eried.eucplanet.service.parseVolumeCurve(s.engineVolumeAutoCurve)
        mufflerKey = s.engineMuffler
        gearboxKey = s.engineGearbox
        idleBehavior = s.engineIdleBehavior
        decelChar = s.engineDecelChar
        engineBrakeMode = s.engineBrake
        duckMode = s.engineDuckOnVoice
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
            // Reset telemetry-state so the next wheel session starts clean:
            // pwmEverNonZero is sticky to avoid flapping back to derivative-load
            // mid-ride on a momentary 0% PWM read, but across BLE sessions
            // (potentially a different wheel) we want to re-discover capability.
            pwmEverNonZero = false
            lastPwm = 0f
            lastSpeedKmh = 0f
            lastTelemetryAtMs = 0L
            smoothedRpm = 0f
            smoothedLoad = 0f
            brakeEnvelope = 0f
            decelEnvelope = 0f
        }
    }

    /** Voice service hook, call when a TTS announcement starts/stops. */
    fun setVoiceActive(active: Boolean) {
        voiceActive = active
    }

    /**
     * Tell the engine what the wheel's top speed is so the speed→RPM map matches
     * the wheel. WheelService wires this to WheelRepository.maxSpeedCap.
     */
    fun setMaxSpeedRef(kmh: Float) {
        if (kmh > 5f) maxSpeedRefKmh = kmh
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
        val savedMuffler = mufflerKey
        val savedGearbox = gearboxKey
        val savedPwmEverNonZero = pwmEverNonZero
        profile = resolveProfile(key)
        if (volume != null) masterVolume = volume.coerceIn(0f, 1f)
        if (muffler != null) mufflerKey = muffler
        if (gearbox != null) gearboxKey = gearbox
        // Scenario-driven paths use pushTelemetry which only honors pwm-based load once
        // it's seen a non-zero pwm; prime the flag so brake/decel scenarios behave.
        pwmEverNonZero = true
        Thread {
            try {
                start()
                when (scenario) {
                    "GEARBOX" -> runGearboxScenario(durationMs)
                    "BRAKE"   -> runBrakeScenario(durationMs)
                    "DECEL"   -> runDecelScenario(durationMs)
                    else      -> runDefaultSweep(durationMs)
                }
                Thread.sleep(250)
                stop()
            } finally {
                profile = savedProfile
                masterVolume = savedVolume
                mufflerKey = savedMuffler
                gearboxKey = savedGearbox
                pwmEverNonZero = savedPwmEverNonZero
                // Reset smoothing state so the next real telemetry tick doesn't carry over
                // the scenario's rpm/load, avoids a transient when the user goes riding right after.
                smoothedRpm = 0f
                smoothedLoad = 0f
                brakeEnvelope = 0f
                decelEnvelope = 0f
                lastPwm = 0f
                lastTelemetryAtMs = 0L
            }
        }.start()
    }

    private fun runDefaultSweep(durationMs: Long) {
        val steps = 25
        val halfDur = durationMs / 2
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            pushParamsDirect(rpmFraction = t, load = t * 0.7f)
            Thread.sleep(halfDur / steps)
        }
        for (i in 0..steps) {
            val t = 1f - i.toFloat() / steps
            pushParamsDirect(rpmFraction = t, load = t * 0.3f)
            Thread.sleep(halfDur / steps)
        }
    }

    /** Speed sweep 0..maxSpeedRef km/h so the gearbox actually crosses gear thresholds. */
    private fun runGearboxScenario(durationMs: Long) {
        val topKmh = maxSpeedRefKmh
        val steps = 50
        for (i in 0..steps) {
            val s = topKmh * (i.toFloat() / steps)
            pushTelemetry(speedKmh = s, pwmPercent = 55f)
            Thread.sleep(durationMs / steps)
        }
    }

    /** Steady coast at speed with throttle closed, engineBrake envelope ramps up. */
    private fun runBrakeScenario(durationMs: Long) {
        val steps = 30
        for (i in 0..steps) {
            pushTelemetry(speedKmh = 22f, pwmPercent = 0f)
            Thread.sleep(durationMs / steps)
        }
    }

    /** Rev up under load, then sharp throttle drop, wakes up decel pops / backfire. */
    private fun runDecelScenario(durationMs: Long) {
        val accelSteps = 18
        val accelDur = (durationMs * 0.55f).toLong()
        for (i in 0..accelSteps) {
            val t = i.toFloat() / accelSteps
            pushTelemetry(speedKmh = 10f + 25f * t, pwmPercent = 30f + 50f * t)
            Thread.sleep(accelDur / accelSteps)
        }
        // Sharp drop, both pwmDrop and smoothedLoad fall, triggering decelTrigger + pops
        pushTelemetry(speedKmh = 35f, pwmPercent = 0f)
        val tailDur = (durationMs * 0.45f).toLong()
        val tailSteps = 15
        for (i in 0..tailSteps) {
            pushTelemetry(speedKmh = 35f - 25f * (i.toFloat() / tailSteps), pwmPercent = 0f)
            Thread.sleep(tailDur / tailSteps)
        }
    }

    private fun pushParamsDirect(rpmFraction: Float, load: Float) {
        val rpm = profile.idleRpm + (profile.maxRpm - profile.idleRpm) * rpmFraction.coerceIn(0f, 1f)
        smoothedRpm = rpm
        smoothedLoad = load
        idleEnvelope = 1f
        emit()
    }

    /** Telemetry tick from WheelService (typically 5-20 Hz). */
    fun pushTelemetry(speedKmh: Float, pwmPercent: Float) {
        val now = System.currentTimeMillis()
        if (lastTelemetryAtMs == 0L) lastTelemetryAtMs = now
        val dt = ((now - lastTelemetryAtMs).coerceAtLeast(1L)) / 1000f
        lastTelemetryAtMs = now

        // PWM normalize to 0..1
        val pwm01 = (pwmPercent / 100f).coerceIn(0f, 1f)
        if (pwm01 > 0.01f) pwmEverNonZero = true

        // PWM fallback: if a wheel never reports PWM, derive a synthetic load from speed derivative.
        val effectiveLoad = if (pwmEverNonZero) {
            pwm01
        } else {
            val accel = (speedKmh - lastSpeedKmh) / dt    // km/h per second
            // Map: +15 km/h/s (hard accel) -> 0.9 load. -15 (hard decel/regen) -> 0.05.
            val normalized = (accel / 15f).coerceIn(-1f, 1f)
            (0.45f + 0.45f * normalized).coerceIn(0.05f, 0.95f)
        }

        // Target RPM from speed + load + gearbox
        val target = computeTargetRpm(speedKmh, effectiveLoad)

        // Low-pass smoothing
        val rpmAlpha = (dt * 6f).coerceAtMost(1f)         // ~165ms time constant
        smoothedRpm += rpmAlpha * (target - smoothedRpm)
        val loadAlpha = (dt * 8f).coerceAtMost(1f)
        smoothedLoad += loadAlpha * (effectiveLoad - smoothedLoad)

        // Decel detection, sharp PWM drop OR regen (negative-derived load when no PWM).
        val pwmDrop = (lastPwm - pwm01).coerceAtLeast(0f)
        val decelTrigger = pwmDrop > 0.25f && smoothedLoad < 0.3f
        if (decelChar != "SMOOTH" && decelTrigger) {
            // Pop probability is profile × user setting × drop magnitude
            val popMultiplier = when (decelChar) {
                "BACKFIRE" -> 1.5f
                else -> 1f
            }
            val pProb = profile.decelPopProbability * popMultiplier * (pwmDrop * 2f).coerceAtMost(1f)
            if (Math.random() < pProb) pendingPops++
        }
        decelEnvelope *= 0.85f
        if (decelTrigger) decelEnvelope = (decelEnvelope + 0.6f).coerceAtMost(1f)
        lastPwm = pwm01

        // Engine brake: rises when rolling on closed throttle (sustained low load + still moving),
        // falls back when the rider rolls back onto throttle.
        val absSpeedNow = abs(speedKmh)
        val rolling = absSpeedNow > 4f
        val onBrake = rolling && smoothedLoad < 0.18f
        val brakeIntensity = when (engineBrakeMode) {
            "OFF" -> 0f
            "STRONG" -> 1f
            else -> 0.55f                            // LIGHT
        }
        val targetBrake = if (onBrake) brakeIntensity else 0f
        // Slow attack (~700 ms), faster release (~350 ms)
        val brakeAlpha = (dt * if (targetBrake > brakeEnvelope) 1.4f else 3f).coerceAtMost(1f)
        brakeEnvelope += brakeAlpha * (targetBrake - brakeEnvelope)

        // The curve IS the engine volume, no separate fixed slider any more. Evaluate
        // the 4-point pchip at the current speed and smooth the result so a sudden
        // speed change doesn't pop the gain.
        val targetVol = if (volumeAutoCurve.isNotEmpty())
            com.eried.eucplanet.service.pchipInterpolate(volumeAutoCurve, abs(speedKmh))
                .coerceIn(0f, 1f)
        else 1f
        smoothedSpeedMult += (targetVol - smoothedSpeedMult) * (dt * 4f).coerceAtMost(1f)

        // Idle envelope
        val moving = abs(speedKmh) > 0.3f
        if (moving) lastMovingAtMs = now
        val parkedMs = now - lastMovingAtMs
        val targetIdle = when (idleBehavior) {
            "ALWAYS" -> 1f
            "MOVING" -> if (moving) 1f else 0f
            "FADE" -> {
                // Stay at full for first 8 seconds, then linear fade to 0 over next 12s
                when {
                    parkedMs < 8000L -> 1f
                    parkedMs > 20000L -> 0f
                    else -> 1f - ((parkedMs - 8000L) / 12000f)
                }
            }
            else -> 1f
        }
        idleEnvelope += (targetIdle - idleEnvelope) * (dt * 1.5f).coerceAtMost(1f)

        // Rev detector
        revDetector.update(now, speedKmh)

        // Duck on voice
        val targetDuck = when {
            !voiceActive -> 1f
            duckMode == "PAUSE" -> 0f
            duckMode == "DUCK" -> 0.25f         // -12 dB
            else -> 1f
        }
        voiceDuckGain += (targetDuck - voiceDuckGain) * (dt * 10f).coerceAtMost(1f)

        lastSpeedKmh = speedKmh

        emit()
    }

    private fun computeTargetRpm(speedKmh: Float, load: Float): Float {
        val absSpeed = abs(speedKmh)
        val maxRef = maxSpeedRefKmh
        val gearless = profile.gearless || gearboxKey == "OFF"
        val gearCount = when (gearboxKey) {
            "SIX" -> 6
            "FOUR" -> 4
            else -> 0
        }
        val baseFrac = if (gearless || gearCount == 0) {
            // sqrt curve against the wheel's top speed, typical EUC cruise (10-30 km/h) is a
            // small fraction of a fast wheel's max (90-150). Linear would leave the engine at
            // idle most of the time; sqrt pushes 20 km/h on a 100 km/h wheel up to ~45% rev band.
            sqrt((absSpeed / maxRef).coerceIn(0f, 1f))
        } else {
            // Gearbox: each gear covers a band of speed; RPM ramps 0..1 within each, then drops on shift.
            val perGear = maxRef / gearCount
            val gearIdx = (absSpeed / perGear).toInt().coerceAtMost(gearCount - 1)
            val withinGear = (absSpeed - gearIdx * perGear) / perGear
            withinGear.coerceIn(0f, 1f)
        }
        val rpmRange = profile.maxRpm - profile.idleRpm
        // Idle floor + speed-derived rev band + load tops it off
        return profile.idleRpm + rpmRange * (0.65f * baseFrac + 0.35f * load)
    }

    private fun emit() {
        val now = System.currentTimeMillis()
        val pops = pendingPops
        pendingPops = 0
        val muff = when (mufflerKey) {
            "OPEN" -> 1f
            "MUFFLED" -> 0.15f
            else -> 0.55f                                // HALF
        }
        // The smoothed speed curve drives the master gain directly, no separate
        // slider any more. voiceDuckGain dips when TTS is speaking.
        val gain = smoothedSpeedMult * voiceDuckGain
        paramsSnapshot = EngineParams(
            rpm = smoothedRpm,
            load = smoothedLoad,
            decelAmount = decelEnvelope,
            idleAmount = idleEnvelope.coerceIn(0f, 1f),
            masterGain = gain,
            pendingPops = pops,
            revBump = revDetector.currentBump(now),
            engineBrakeAmount = brakeEnvelope.coerceIn(0f, 1f),
            profile = profile,
            mufflerOpenness = muff
        )
        // Sample-based engine takes its parameters from the same snapshot.
        val rpmRange = (profile.maxRpm - profile.idleRpm).coerceAtLeast(1)
        val effRpm = smoothedRpm + revDetector.currentBump(now)
        val rpmNorm = ((effRpm - profile.idleRpm) / rpmRange).coerceIn(0f, 1f)
        val sampledVolume = gain * idleEnvelope.coerceIn(0f, 1f)

        if (compositionPlayer.isPlaying()) {
            compositionPlayer.update(rpmNorm, sampledVolume)
            if (decelEnvelope > 0.6f && pops == 0) {
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
        revDetector.reset()
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

    private fun isHeadphonesActive(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    companion object {
        private const val TAG = "EngineSoundEngine"
    }
}
