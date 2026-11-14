package com.eried.eucplanet.audio

import com.eried.eucplanet.service.parseVolumeCurve
import com.eried.eucplanet.service.pchipInterpolate
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The virtual engine's motion: turns the wheel's (speed, pwm) ticks into the
 * smoothed [EngineParams] that [EngineSynth] renders. Pure
 * Kotlin with no platform in it, so the same model runs on both phones inside
 * [EngineSoundEngine] and offline in the ride renderer that writes WAVs for
 * listening tests.
 *
 * Not thread-safe; the owner serialises [tick], [direct] and the resets.
 */
class EngineDynamics {

    var profile: EngineProfile = EngineProfile.byKey("FOUR_STROKE_SINGLE")
    var mufflerKey: String = "HALF"
    var gearboxKey: String = "FOUR"
    var idleBehavior: String = "FADE"
    var decelChar: String = "STANDARD"
    var engineBrakeMode: String = "LIGHT"
    var duckMode: String = "DUCK"
    /** Parsed 4-point speed curve; empty means full volume at every speed. */
    var volumeAutoCurve: List<Pair<Float, Float>> = emptyList()
    /** The wheel's top speed, so a 50 km/h wheel revs out at 50 and a 150 km/h wheel later. */
    var maxSpeedRefKmh: Float = 90f
    /** True while a TTS announcement plays; the engine ducks under it. */
    var voiceActive: Boolean = false
    /** Becomes true once real PWM is seen, so the speed-derivative fallback is left for good. */
    var pwmEverNonZero: Boolean = false

    var smoothedRpm: Float = 0f
        private set
    var smoothedLoad: Float = 0f
        private set
    var idleEnvelope: Float = 0f
        private set
    var decelEnvelope: Float = 0f
        private set
    var brakeEnvelope: Float = 0f
        private set
    /** Smoothed speed-based gain so abrupt speed changes don't click the volume. */
    private var smoothedSpeedMult: Float = 1f
    private var lastPwm: Float = 0f
    private var lastSpeedKmh: Float = 0f
    private var lastMovingAtMs: Long = 0L
    private var lastTelemetryAtMs: Long = 0L
    private var pendingPops: Int = 0
    private var voiceDuckGain: Float = 1f
    private val revDetector = RevDetector()
    /** Random source for decel pops; the renderer seeds it so WAVs are repeatable. */
    var random: () -> Double = { Random.nextDouble() }

    fun applyCurve(raw: String) { volumeAutoCurve = parseVolumeCurve(raw) }

    /** A new wheel session: forget what the last wheel taught us. */
    fun resetSession() {
        pwmEverNonZero = false
        lastPwm = 0f
        lastSpeedKmh = 0f
        lastTelemetryAtMs = 0L
        smoothedRpm = 0f
        smoothedLoad = 0f
        brakeEnvelope = 0f
        decelEnvelope = 0f
    }

    /** After a preview: drop the scenario's motion so the next real tick starts clean. */
    fun resetMotion() {
        smoothedRpm = 0f
        smoothedLoad = 0f
        brakeEnvelope = 0f
        decelEnvelope = 0f
        lastPwm = 0f
        lastTelemetryAtMs = 0L
    }

    fun resetRev() = revDetector.reset()

    /** Preview sweep: the rev band fraction and load set outright, no smoothing. */
    fun direct(nowMs: Long, rpmFraction: Float, load: Float): EngineParams {
        smoothedRpm = profile.idleRpm + (profile.maxRpm - profile.idleRpm) * rpmFraction.coerceIn(0f, 1f)
        smoothedLoad = load
        idleEnvelope = 1f
        return snapshot(nowMs)
    }

    /** A telemetry tick (5 to 20 Hz from the wheel). */
    fun tick(nowMs: Long, speedKmh: Float, pwmPercent: Float): EngineParams {
        val now = nowMs
        if (lastTelemetryAtMs == 0L) lastTelemetryAtMs = now
        val dt = ((now - lastTelemetryAtMs).coerceAtLeast(1L)) / 1000f
        lastTelemetryAtMs = now

        val pwm01 = (pwmPercent / 100f).coerceIn(0f, 1f)
        if (pwm01 > 0.01f) pwmEverNonZero = true

        // A wheel that never reports PWM gets a load made from the speed derivative.
        val effectiveLoad = if (pwmEverNonZero) {
            pwm01
        } else {
            val accel = (speedKmh - lastSpeedKmh) / dt    // km/h per second
            // +15 km/h/s (hard accel) -> 0.9 load. -15 (hard decel/regen) -> 0.05.
            val normalized = (accel / 15f).coerceIn(-1f, 1f)
            (0.45f + 0.45f * normalized).coerceIn(0.05f, 0.95f)
        }

        val target = computeTargetRpm(speedKmh, effectiveLoad)

        val rpmAlpha = (dt * 6f).coerceAtMost(1f)         // ~165ms time constant
        smoothedRpm += rpmAlpha * (target - smoothedRpm)
        val loadAlpha = (dt * 8f).coerceAtMost(1f)
        smoothedLoad += loadAlpha * (effectiveLoad - smoothedLoad)

        // Decel: a sharp PWM drop with the load already low.
        val pwmDrop = (lastPwm - pwm01).coerceAtLeast(0f)
        val decelTrigger = pwmDrop > 0.25f && smoothedLoad < 0.3f
        if (decelChar != "SMOOTH" && decelTrigger) {
            val popMultiplier = if (decelChar == "BACKFIRE") 1.5f else 1f
            val pProb = profile.decelPopProbability * popMultiplier * (pwmDrop * 2f).coerceAtMost(1f)
            if (random() < pProb) pendingPops++
        }
        decelEnvelope *= 0.85f
        if (decelTrigger) decelEnvelope = (decelEnvelope + 0.6f).coerceAtMost(1f)
        lastPwm = pwm01

        // Engine brake: rolling on a closed throttle. Slow attack (~700 ms), faster release (~350 ms).
        val rolling = abs(speedKmh) > 4f
        val onBrake = rolling && smoothedLoad < 0.18f
        val brakeIntensity = when (engineBrakeMode) {
            "OFF" -> 0f
            "STRONG" -> 1f
            else -> 0.55f                            // LIGHT
        }
        val targetBrake = if (onBrake) brakeIntensity else 0f
        val brakeAlpha = (dt * if (targetBrake > brakeEnvelope) 1.4f else 3f).coerceAtMost(1f)
        brakeEnvelope += brakeAlpha * (targetBrake - brakeEnvelope)

        // The speed curve is the engine volume.
        val targetVol = if (volumeAutoCurve.isNotEmpty())
            pchipInterpolate(volumeAutoCurve, abs(speedKmh)).coerceIn(0f, 1f)
        else 1f
        smoothedSpeedMult += (targetVol - smoothedSpeedMult) * (dt * 4f).coerceAtMost(1f)

        // Idle envelope: parked engines fade per the rider's choice.
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
            duckMode == "DUCK" -> 0.25f         // -12 dB
            else -> 1f
        }
        voiceDuckGain += (targetDuck - voiceDuckGain) * (dt * 10f).coerceAtMost(1f)

        lastSpeedKmh = speedKmh
        return snapshot(now)
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
            // sqrt against the wheel's top speed: EUC cruise (10-30 km/h) is a small
            // fraction of a fast wheel's max, linear would leave the engine at idle.
            sqrt((absSpeed / maxRef).coerceIn(0f, 1f))
        } else {
            // Each gear covers a band of speed; RPM ramps within it, then drops on the shift.
            val perGear = maxRef / gearCount
            val gearIdx = (absSpeed / perGear).toInt().coerceAtMost(gearCount - 1)
            val withinGear = (absSpeed - gearIdx * perGear) / perGear
            withinGear.coerceIn(0f, 1f)
        }
        val rpmRange = profile.maxRpm - profile.idleRpm
        return profile.idleRpm + rpmRange * (0.65f * baseFrac + 0.35f * load)
    }

    private fun snapshot(nowMs: Long): EngineParams {
        val pops = pendingPops
        pendingPops = 0
        val muff = when (mufflerKey) {
            "OPEN" -> 1f
            "MUFFLED" -> 0.15f
            else -> 0.55f                                // HALF
        }
        return EngineParams(
            rpm = smoothedRpm,
            load = smoothedLoad,
            decelAmount = decelEnvelope,
            idleAmount = idleEnvelope.coerceIn(0f, 1f),
            masterGain = smoothedSpeedMult * voiceDuckGain,
            pendingPops = pops,
            revBump = revDetector.currentBump(nowMs),
            engineBrakeAmount = brakeEnvelope.coerceIn(0f, 1f),
            profile = profile,
            mufflerOpenness = muff
        )
    }
}
