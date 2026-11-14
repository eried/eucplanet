package com.eried.eucplanet.audio

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Motor sound generator. Plays a virtual engine driven by real-time
 * (speed, pwm) telemetry. Lives as a singleton; WheelService feeds telemetry
 * and lifecycle events.
 *
 * The motion model is [EngineDynamics] (pure Kotlin, shared with the offline
 * ride renderer) and every engine, recorded or synthesised, is rendered by
 * [EngineSynth] into one [EngineAudioOutput]. This class owns the settings,
 * the previews and the start / stop rules, the same on both phones; the
 * phone gives it the output and [bankBytes], which reads a recorded
 * engine's grain bank by its asset name (`res/raw` on Android, the app
 * bundle on iOS).
 *
 * Threading:
 *  - Main / coroutine thread calls [applySettings], [pushTelemetry], [start], [stop],
 *    [previewProfile]. These mutate volatile state.
 *  - The output's producer reads the volatile params snapshot every buffer fill
 *    through [EngineProducer.render]. Allocations are kept off the hot path.
 */
class EngineSoundEngine(
    private val output: EngineAudioOutput,
    private val bankBytes: (asset: String) -> ByteArray?,
) {

    private val sampleRate = SAMPLE_RATE
    private val bufferFrames = BUFFER_FRAMES

    @Volatile private var running: Boolean = false

    @Volatile private var paramsSnapshot: EngineParams = EngineParams.SILENT
    private val synth = EngineSynth(sampleRate) { asset -> openBank(asset) }

    // Guards the model: pushTelemetry runs on the service's worker thread (and
    // the preview thread) while the disconnect reset in setConnected runs on
    // the main thread. Never held across start()/stop().
    private val telemetryLock = SynchronizedObject()
    private val model = EngineDynamics()
    private val profile: EngineProfile get() = model.profile

    private var masterVolume: Float = 0.6f
    private var volumeAutoEnabled: Boolean = false
    private var headphonesOnly: Boolean = false
    /** Tracks the previous applySettings()'s enabled flag so we only log toggle edges. */
    private var lastAppliedEnabled: Boolean = false
    private var connected: Boolean = false

    /** Runs the previews, which sleep between their steps. */
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A recorded engine's grain bank, or null when the file is missing or broken. */
    private fun openBank(asset: String): GrainBank? {
        val bytes = bankBytes(asset)
        if (bytes == null) {
            Log.w(TAG, "No grain bank $asset, the synthesised engine stands in")
            return null
        }
        return GrainBank.load(sampleRate) { bytes }
    }

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
            val pathKind = if (profile.grainAsset != null) "recorded" else profile.kind.name.lowercase()
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
        // Engine type swap while running: restart so the new engine starts clean.
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
        previewScope.launch {
            try {
                start()
                val script = EngineRide.preview(scenario, durationMs, model.maxSpeedRefKmh)
                for (step in script) {
                    if (step.direct) pushParamsDirect(step.rpmFraction, step.load)
                    else pushTelemetry(step.speedKmh, step.pwmPercent)
                    delay(step.holdMs)
                }
                delay(250)
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
        }
    }

    private fun pushParamsDirect(rpmFraction: Float, load: Float) {
        synchronized(telemetryLock) {
            val now = System.currentTimeMillis()
            paramsSnapshot = model.direct(now, rpmFraction, load)
        }
    }

    /** Telemetry tick from WheelService (typically 5-20 Hz). */
    fun pushTelemetry(speedKmh: Float, pwmPercent: Float) {
        synchronized(telemetryLock) {
            val now = System.currentTimeMillis()
            paramsSnapshot = model.tick(now, speedKmh, pwmPercent)
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
        output.requestFocus()
        // A recorded engine's bank is read here, off the audio thread.
        synth.preload(profile)
        if (!output.start(sampleRate, bufferFrames, producer)) running = false
    }

    /** Stop the audio producer thread and release resources. Idempotent. */
    fun stop() {
        running = false
        output.stop()
        output.abandonFocus()
        synth.reset()
        synchronized(telemetryLock) { model.resetRev() }
    }

    /** What the output's producer pulls: the next buffer from the latest params snapshot. */
    private val producer = object : EngineProducer {
        override fun render(out: FloatArray): Boolean {
            if (!running) return false
            synth.render(paramsSnapshot, out, 0, out.size)
            return true
        }

        override fun failed() {
            running = false
        }
    }

    private fun isHeadphonesActive(): Boolean = output.externalOutputActive()

    companion object {
        private const val TAG = "EngineSoundEngine"
        const val SAMPLE_RATE = 44100
        /** ~23 ms at 44.1 kHz, small enough for live response. */
        const val BUFFER_FRAMES = 1024
    }
}
