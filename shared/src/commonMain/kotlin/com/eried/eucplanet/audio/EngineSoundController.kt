package com.eried.eucplanet.audio

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.util.nowEpochMillis
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Motor sound controller — the shared, platform-agnostic half of Android's
 * EngineSoundEngine: it maps live (speed, pwm) telemetry to an [EngineParams]
 * snapshot (smoothing, gearbox rev map, decel pops, engine-brake whine, idle
 * fade, speed-volume curve, voice ducking) and renders the synth into PCM. The
 * platform audio output (iOS AVAudioEngine `EngineAudioBridge`) pulls buffers via
 * [render] and is started/stopped through [nativeStart] / [nativeStop].
 *
 * iOS v1 is synth-only (ICE firing model + SYNTH osc, all profiles render through
 * [EngineSynth]); Android's sampled / composition engines aren't ported.
 */
object EngineSoundController {
    const val SAMPLE_RATE = 44100
    private val synth = EngineSynth(SAMPLE_RATE)

    @Volatile private var params = EngineParams.SILENT

    var nativeStart: (() -> Unit)? = null
    var nativeStop: (() -> Unit)? = null
    private var running = false

    // Reference top speed used to map km/h → rev band (updated from the wheel).
    @Volatile private var maxSpeedRefKmh: Float = 90f

    // Settings-derived config.
    private var profile: EngineProfile = EngineProfile.byKey("FOUR_STROKE_SINGLE")
    private var enabled = false
    private var masterVolume = 0.6f
    private var volumeAutoCurve: List<Pair<Float, Float>> = emptyList()
    private var mufflerKey = "HALF"
    private var gearboxKey = "FOUR"
    private var idleBehavior = "FADE"
    private var decelChar = "STANDARD"
    private var engineBrakeMode = "LIGHT"
    private var duckMode = "DUCK"

    // Smoothing / envelope state.
    private var smoothedRpm = 0f
    private var smoothedLoad = 0f
    private var smoothedSpeedMult = 1f
    private var lastPwm = 0f
    private var pwmEverNonZero = false
    private var lastSpeedKmh = 0f
    private var lastMovingAtMs = 0L
    private var lastTelemetryAtMs = 0L
    private var idleEnvelope = 0f
    private var decelEnvelope = 0f
    private var brakeEnvelope = 0f
    private var pendingPops = 0
    private var voiceActive = false
    private var voiceDuckGain = 1f
    private val revDetector = RevDetector()

    /** Apply settings (call on every AppSettings change). */
    fun applySettings(s: AppSettings) {
        val previousKey = profile.key
        profile = EngineProfile.byKey(s.engineType)
        enabled = s.engineSoundEnabled
        masterVolume = s.engineVolume.coerceIn(0f, 1f)
        volumeAutoCurve = parseVolumeCurve(s.engineVolumeAutoCurve)
        mufflerKey = s.engineMuffler
        gearboxKey = s.engineGearbox
        idleBehavior = s.engineIdleBehavior
        decelChar = s.engineDecelChar
        engineBrakeMode = s.engineBrake
        duckMode = s.engineDuckOnVoice
        if (!enabled) { stop(); return }
        if (running && previousKey != profile.key) { synth.reset() }
    }

    /** Wheel connect / disconnect. */
    fun setConnected(isConnected: Boolean, s: AppSettings?) {
        if (isConnected && s?.engineSoundEnabled == true) start()
        else if (!isConnected) {
            stop()
            pwmEverNonZero = false; lastPwm = 0f; lastSpeedKmh = 0f; lastTelemetryAtMs = 0L
            smoothedRpm = 0f; smoothedLoad = 0f; brakeEnvelope = 0f; decelEnvelope = 0f
        }
    }

    fun setVoiceActive(active: Boolean) { voiceActive = active }
    fun setMaxSpeedRef(kmh: Float) { if (kmh > 5f) maxSpeedRefKmh = kmh }

    /** Feed a live telemetry tick — computes the next [EngineParams] snapshot. */
    fun pushTelemetry(speedKmh: Float, pwmPercent: Float) {
        if (!running) return
        val now = nowEpochMillis()
        if (lastTelemetryAtMs == 0L) lastTelemetryAtMs = now
        val dt = ((now - lastTelemetryAtMs).coerceAtLeast(1L)) / 1000f
        lastTelemetryAtMs = now

        val pwm01 = (pwmPercent / 100f).coerceIn(0f, 1f)
        if (pwm01 > 0.01f) pwmEverNonZero = true

        val effectiveLoad = if (pwmEverNonZero) pwm01 else {
            val accel = (speedKmh - lastSpeedKmh) / dt
            val normalized = (accel / 15f).coerceIn(-1f, 1f)
            (0.45f + 0.45f * normalized).coerceIn(0.05f, 0.95f)
        }

        val target = computeTargetRpm(speedKmh, effectiveLoad)
        val rpmAlpha = (dt * 6f).coerceAtMost(1f)
        smoothedRpm += rpmAlpha * (target - smoothedRpm)
        val loadAlpha = (dt * 8f).coerceAtMost(1f)
        smoothedLoad += loadAlpha * (effectiveLoad - smoothedLoad)

        val pwmDrop = (lastPwm - pwm01).coerceAtLeast(0f)
        val decelTrigger = pwmDrop > 0.25f && smoothedLoad < 0.3f
        if (decelChar != "SMOOTH" && decelTrigger) {
            val popMultiplier = if (decelChar == "BACKFIRE") 1.5f else 1f
            val pProb = profile.decelPopProbability * popMultiplier * (pwmDrop * 2f).coerceAtMost(1f)
            if (Random.nextDouble() < pProb) pendingPops++
        }
        decelEnvelope *= 0.85f
        if (decelTrigger) decelEnvelope = (decelEnvelope + 0.6f).coerceAtMost(1f)
        lastPwm = pwm01

        val rolling = abs(speedKmh) > 4f
        val onBrake = rolling && smoothedLoad < 0.18f
        val brakeIntensity = when (engineBrakeMode) {
            "OFF" -> 0f; "STRONG" -> 1f; else -> 0.55f
        }
        val targetBrake = if (onBrake) brakeIntensity else 0f
        val brakeAlpha = (dt * if (targetBrake > brakeEnvelope) 1.4f else 3f).coerceAtMost(1f)
        brakeEnvelope += brakeAlpha * (targetBrake - brakeEnvelope)

        val targetVol = if (volumeAutoCurve.isNotEmpty())
            pchipInterpolate(volumeAutoCurve, abs(speedKmh)).coerceIn(0f, 1f) else 1f
        smoothedSpeedMult += (targetVol - smoothedSpeedMult) * (dt * 4f).coerceAtMost(1f)

        val moving = abs(speedKmh) > 0.3f
        if (moving) lastMovingAtMs = now
        val parkedMs = now - lastMovingAtMs
        val targetIdle = when (idleBehavior) {
            "ALWAYS" -> 1f
            "MOVING" -> if (moving) 1f else 0f
            "FADE" -> when {
                parkedMs < 8000L -> 1f
                parkedMs > 20000L -> 0f
                else -> 1f - ((parkedMs - 8000L) / 12000f)
            }
            else -> 1f
        }
        idleEnvelope += (targetIdle - idleEnvelope) * (dt * 1.5f).coerceAtMost(1f)

        revDetector.update(now, speedKmh)

        val targetDuck = when {
            !voiceActive -> 1f
            duckMode == "PAUSE" -> 0f
            duckMode == "DUCK" -> 0.25f
            else -> 1f
        }
        voiceDuckGain += (targetDuck - voiceDuckGain) * (dt * 10f).coerceAtMost(1f)

        lastSpeedKmh = speedKmh
        emit(now)
    }

    private fun computeTargetRpm(speedKmh: Float, load: Float): Float {
        val absSpeed = abs(speedKmh)
        val maxRef = maxSpeedRefKmh
        val gearless = profile.gearless || gearboxKey == "OFF"
        val gearCount = when (gearboxKey) { "SIX" -> 6; "FOUR" -> 4; else -> 0 }
        val baseFrac = if (gearless || gearCount == 0) {
            sqrt((absSpeed / maxRef).coerceIn(0f, 1f))
        } else {
            val perGear = maxRef / gearCount
            val gearIdx = (absSpeed / perGear).toInt().coerceAtMost(gearCount - 1)
            val withinGear = (absSpeed - gearIdx * perGear) / perGear
            withinGear.coerceIn(0f, 1f)
        }
        val rpmRange = profile.maxRpm - profile.idleRpm
        return profile.idleRpm + rpmRange * (0.65f * baseFrac + 0.35f * load)
    }

    private fun emit(now: Long) {
        val pops = pendingPops
        pendingPops = 0
        val muff = when (mufflerKey) { "OPEN" -> 1f; "MUFFLED" -> 0.15f; else -> 0.55f }
        val gain = smoothedSpeedMult * voiceDuckGain * masterVolume
        params = EngineParams(
            rpm = smoothedRpm,
            load = smoothedLoad,
            decelAmount = decelEnvelope,
            idleAmount = idleEnvelope.coerceIn(0f, 1f),
            masterGain = gain,
            pendingPops = pops,
            revBump = revDetector.currentBump(now),
            engineBrakeAmount = brakeEnvelope.coerceIn(0f, 1f),
            profile = profile,
            mufflerOpenness = muff,
        )
    }

    /** Fill [out] with [count] mono Float32 samples — called from the audio thread. */
    fun render(out: FloatArray, count: Int) { synth.render(params, out, 0, count) }

    fun start() { if (running) return; running = true; nativeStart?.invoke() }
    fun stop() {
        if (!running) return
        running = false
        params = EngineParams.SILENT
        synth.reset()
        nativeStop?.invoke()
    }

    // --- volume-curve helpers (ported from Android's AutomationManager) ---
    private fun parseVolumeCurve(raw: String): List<Pair<Float, Float>> =
        raw.split(",").mapNotNull { pair ->
            val parts = pair.trim().split(":")
            if (parts.size == 2) {
                val speed = parts[0].toFloatOrNull(); val mult = parts[1].toFloatOrNull()
                if (speed != null && mult != null) speed to mult else null
            } else null
        }.sortedBy { it.first }

    private fun pchipInterpolate(points: List<Pair<Float, Float>>, x: Float): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return points[0].second.coerceIn(0f, 2f)
        val sorted = points.sortedBy { it.first }
        if (x <= sorted.first().first) return sorted.first().second.coerceIn(0f, 2f)
        if (x >= sorted.last().first) return sorted.last().second.coerceIn(0f, 2f)
        val n = sorted.size
        val h = FloatArray(n - 1); val d = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            h[i] = sorted[i + 1].first - sorted[i].first
            d[i] = (sorted[i + 1].second - sorted[i].second) / h[i]
        }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1].sign != d[i].sign || d[i - 1] == 0f || d[i] == 0f) 0f
            else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
            }
        }
        var k = 0
        for (i in 0 until n - 1) {
            if (x >= sorted[i].first && x <= sorted[i + 1].first) { k = i; break }
        }
        val hk = h[k]
        val t = (x - sorted[k].first) / hk
        val t2 = t * t; val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val y = h00 * sorted[k].second + h10 * hk * m[k] + h01 * sorted[k + 1].second + h11 * hk * m[k + 1]
        return y.coerceIn(0f, 2f)
    }
}
