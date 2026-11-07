package com.eried.eucplanet.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * DSP core. Owned by the audio thread, not thread-safe.
 *
 * Renders mono Float32 PCM at [sampleRate] driven by an [EngineParams] snapshot.
 * State (crank, resonators, envelopes) carries across [render] calls so the
 * audio is continuous across buffers. Everything lands where a phone speaker
 * plays, roughly 150 Hz to 6 kHz: the engine's note is carried by the firing
 * rate shaping the pipe's ring, not by a sub-bass fundamental nobody hears.
 *
 * Two paths:
 *  - ICE: each cylinder fires at its angle of the crank cycle ([FiringSchedule])
 *    and drops an exhaust pulse (a thump plus a crack of noise) into a pipe of
 *    three resonators tuned by the profile, with intake roar and valvetrain
 *    noise rising on load, a high ring for 2-strokes and screamers, crackle on
 *    the overrun, pops on the throttle chop.
 *  - SYNTH: a [SynthVoice] (bandlimited saw or square, hum partials, a
 *    turbine of shaped noise and whine, an electric motor) riding on RPM.
 */
class EngineSynth(private val sampleRate: Int = 44100) {

    private val dt: Float = 1f / sampleRate

    // --- ICE state ---
    private var schedule: FiringSchedule? = null
    private var scheduleKey: String? = null
    private var pulseAge: Float = 1e9f           // seconds since the last firing
    private var pulseAmp: Float = 0f
    private var pulseLen: Float = 0.003f
    private val pipe1 = Resonator()
    private val pipe2 = Resonator()
    private val pipe3 = Resonator()
    private val bark = Resonator()
    private val ring = Resonator()
    private val intake = Resonator()
    private val crackleBand = Resonator()
    private var brakePhase: Double = 0.0
    private var gearPhase: Double = 0.0

    // --- SYNTH state ---
    private var oscPhase: Double = 0.0
    private var osc2Phase: Double = 0.0
    private var subPhase: Double = 0.0
    private var lfoPhase: Double = 0.0
    private var lfo2Phase: Double = 0.0
    private var spooledNorm: Float = -1f
    private val rumble = Resonator()
    private val roar = Resonator()
    private val wind = Resonator()
    private val whinePhases = DoubleArray(5)

    // --- shared ---
    private val pink = PinkNoise()
    private var lpf1: Float = 0f
    private var lpf2: Float = 0f
    private var hpf1In: Float = 0f
    private var hpf1Out: Float = 0f
    private var hpf2In: Float = 0f
    private var hpf2Out: Float = 0f
    private val popQueue = ArrayDeque<Pop>(4)
    private var rngState: Int = 0x1A2B3C4D

    private fun nextNoise(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x.toFloat() / Int.MAX_VALUE.toFloat()
    }

    /** Render [count] samples into [out] from [outOffset]. Returns the peak, for diagnostics. */
    fun render(params: EngineParams, out: FloatArray, outOffset: Int, count: Int): Float {
        repeat(params.pendingPops) { popQueue.addLast(Pop(remaining = (sampleRate * 0.16f).toInt())) }

        val profile = params.profile
        val effRpm = (params.rpm + params.revBump).coerceAtLeast(0f)
        val rpmRange = (profile.maxRpm - profile.idleRpm).coerceAtLeast(1)
        val rpmNorm = ((effRpm - profile.idleRpm) / rpmRange).coerceIn(0f, 1f)
        val loadGain = 0.7f + 0.3f * (0.5f * rpmNorm + 0.5f * params.load)
        val masterGain = params.masterGain * params.idleAmount * loadGain * profile.gainTrim * OUT_LEVEL

        // Muffler: two one-pole low-passes in a row. Open pipes 7 kHz, muffled 900 Hz.
        val open = params.mufflerOpenness.coerceIn(0f, 1f)
        val cutoffHz = 900f + (7000f - 900f) * open * open
        val lpAlpha = onePoleAlpha(cutoffHz)
        // Sub cut: two one-pole high-passes at 100 Hz. The speaker plays none of it anyway.
        val hpCoef = 1f - onePoleAlpha(100f)

        var peak = 0f
        if (profile.kind == EngineProfile.Kind.SYNTH) {
            peak = renderSynth(profile, params, rpmNorm, masterGain, lpAlpha, hpCoef, out, outOffset, count)
        } else {
            peak = renderIce(profile, params, effRpm, rpmNorm, masterGain, lpAlpha, hpCoef, out, outOffset, count)
        }
        return peak
    }

    private fun renderIce(
        profile: EngineProfile, params: EngineParams, effRpm: Float, rpmNorm: Float,
        masterGain: Float, lpAlpha: Float, hpCoef: Float, out: FloatArray, outOffset: Int, count: Int
    ): Float {
        if (scheduleKey != profile.key) {
            schedule = FiringSchedule(profile.firingAngles, profile.cycleDegrees)
            scheduleKey = profile.key
            pipe1.reset(); pipe2.reset(); pipe3.reset(); bark.reset(); ring.reset(); intake.reset()
        }
        val sched = schedule!!
        val cyclesPerSec = (effRpm / 60f) * (360f / profile.cycleDegrees)
        val cycleStep = cyclesPerSec * dt
        val load = params.load

        // The pipe: three modes of the exhaust, the lowest tuned by the profile.
        // A hot, fast-flowing pipe rings less than a cold one at idle.
        val q = (1f + 5f * profile.pipeQ) * (1f - 0.45f * rpmNorm)
        val pipeHz = profile.pipeHz * (1f + 0.45f * rpmNorm)
        pipe1.set(pipeHz, q, sampleRate)
        pipe2.set(pipeHz * 2.4f, q * 0.8f, sampleRate)
        pipe3.set(pipeHz * 4.3f, q * 0.6f, sampleRate)
        // The bark: a wide formant that opens with load, the sound of a throttle body.
        bark.set(900f + 500f * load, 1.4f, sampleRate)
        val ringOn = profile.ringHz > 0f && profile.ringAmount > 0f
        if (ringOn) ring.set(profile.ringHz, 14f, sampleRate)
        // The ring comes up with the revs, so a screamer still idles like an engine.
        val ringAmt = profile.ringAmount * 4f * (0.25f + 0.75f * rpmNorm)
        // Intake and valvetrain: pink noise through a mid band that climbs with revs.
        intake.set(1100f + 1400f * rpmNorm, 0.7f, sampleRate)
        crackleBand.set(1800f, 1.2f, sampleRate)

        val pulseSeconds = profile.pulseMs / 1000f
        // Pulses get sharper as the engine spins up; more cylinders share the same breath.
        val crack = profile.compressionTone * (0.55f + 0.45f * rpmNorm)
        val barkAmt = 0.4f + 1.2f * rpmNorm
        val gritAmt = profile.exhaustGrit * (0.05f + 0.95f * load + 0.5f * params.decelAmount)
        val intakeAmt = profile.intakeNoise * (0.12f + 0.88f * load) * (0.2f + 0.8f * rpmNorm)
        val brakeAmt = params.engineBrakeAmount
        // Overrun: the gear train sings and the exhaust crackles when the throttle is shut at speed.
        val firingRate = cyclesPerSec * profile.cylinderCount
        // Faster firing piles pulses into the pipe; each pulse gives a little so the
        // level climbs with the revs without running away. Relative to this engine's
        // own idle rate, so a slow single and a V16 get the same treatment.
        val idleRate = (profile.idleRpm / 60f) * (360f / profile.cycleDegrees) * profile.cylinderCount
        val rateComp = 1f / kotlin.math.sqrt(1f + (firingRate - idleRate).coerceAtLeast(0f) / (2f * idleRate))
        // A slow engine's pulse can be long, but never longer than the gap to the next one.
        val pulseSecondsNow = pulseSeconds.coerceAtMost(0.8f / firingRate.coerceAtLeast(1f))
        val brakeHz = (firingRate * (if (profile.twoStroke) 9f else 7f)).coerceIn(600f, 3400f)
        val cracklePerFiring = 0.35f * brakeAmt

        val twoPi = 2.0 * PI
        var peak = 0f
        for (i in 0 until count) {
            // Crank turns; every firing that the step crosses starts a pulse.
            val fired = sched.advance(cycleStep)
            if (fired > 0) {
                pulseAge = 0f
                pulseLen = pulseSecondsNow
                // A little scatter per firing keeps it from sounding like a machine gun.
                pulseAmp = rateComp * (0.6f + 0.4f * load) * (0.92f + 0.16f * abs(nextNoise()))
                if (brakeAmt > 0.01f && abs(nextNoise()) < cracklePerFiring) {
                    popQueue.addLast(Pop(remaining = (sampleRate * 0.05f).toInt(), gain = 0.35f))
                }
            }
            // The pulse: a pressure thump (raised cosine) and a crack of noise on its front.
            var excite = 0f
            if (pulseAge < pulseLen) {
                val t = pulseAge / pulseLen
                val thump = 0.5f - 0.5f * cos((twoPi * t).toFloat())
                val crackEnv = exp(-9f * t)
                excite = pulseAmp * (thump * (1.2f - 0.6f * crack) + crackEnv * crack * 0.9f * nextNoise())
            }
            pulseAge += dt

            // Pops: a bang through the same pipe, decaying.
            var popIn = 0f
            if (popQueue.isNotEmpty()) popIn = renderPops()

            val pipeIn = excite + popIn * 3f + gritAmt * 0.25f * nextNoise()
            var sample = pipe1.process(pipeIn) * 0.8f +
                pipe2.process(pipeIn) * 0.9f +
                pipe3.process(pipeIn) * 0.6f +
                bark.process(pipeIn * load) * barkAmt
            if (ringOn) sample += ring.process(excite * crack) * ringAmt

            // Air: intake roar, valve clatter, and exhaust grit.
            val pinkN = pink.next()
            sample += intake.process(pinkN) * intakeAmt * 2.5f
            sample += gritAmt * 0.12f * pinkN

            // Overrun whine and crackle.
            if (brakeAmt > 0.001f) {
                brakePhase += brakeHz * dt
                if (brakePhase >= 1.0) brakePhase -= 1.0
                sample += brakeAmt * (sin(twoPi * brakePhase).toFloat() * 0.05f + crackleBand.process(nextNoise()) * 0.07f)
            }

            // Muffler, sub cut, soft clip.
            lpf1 += lpAlpha * (sample - lpf1)
            lpf2 += lpAlpha * (lpf1 - lpf2)
            val h1 = hpCoef * (hpf1Out + lpf2 - hpf1In); hpf1In = lpf2; hpf1Out = h1
            val h2 = hpCoef * (hpf2Out + h1 - hpf2In); hpf2In = h1; hpf2Out = h2
            val o = softClip(h2 * masterGain)
            out[outOffset + i] = o
            val a = abs(o)
            if (a > peak) peak = a
        }
        return peak
    }

    private fun renderSynth(
        profile: EngineProfile, params: EngineParams, rpmNorm: Float,
        masterGain: Float, lpAlpha: Float, hpCoef: Float, out: FloatArray, outOffset: Int, count: Int
    ): Float {
        val load = params.load
        val voice = profile.voice
        // A turbine spools; the rest follow the revs at once.
        val targetNorm = rpmNorm
        if (spooledNorm < 0f) spooledNorm = targetNorm
        val spoolAlpha = if (voice == SynthVoice.TURBINE) (count * dt) / (if (targetNorm > spooledNorm) 0.5f else 0.9f) else 1f
        spooledNorm += (targetNorm - spooledNorm) * spoolAlpha.coerceAtMost(1f)
        val n = spooledNorm
        val freq = profile.synthBaseHz + (profile.synthMaxHz - profile.synthBaseHz) * n
        val wobble = profile.synthFmDepth
        val brakeAmt = params.engineBrakeAmount
        val grit = profile.exhaustGrit

        when (voice) {
            SynthVoice.TURBINE -> {
                rumble.set(260f + 120f * n, 0.9f, sampleRate)
                roar.set(1300f + 900f * n, 0.6f, sampleRate)
            }
            SynthVoice.ELECTRIC -> wind.set(2500f + 1500f * n, 0.5f, sampleRate)
            else -> wind.set(1800f, 0.8f, sampleRate)
        }

        val twoPi = 2.0 * PI
        var peak = 0f
        for (i in 0 until count) {
            // Two slow LFOs: vibrato and the tremolo wobble.
            lfoPhase += (4.5f + 2.5f * n) * dt
            if (lfoPhase >= 1.0) lfoPhase -= 1.0
            lfo2Phase += (0.7f + 1.1f * n) * dt
            if (lfo2Phase >= 1.0) lfo2Phase -= 1.0
            val vib = sin(twoPi * lfoPhase).toFloat()
            val trem = 0.5f + 0.5f * sin(twoPi * lfo2Phase).toFloat()
            val f = freq * (1f + 0.012f * wobble * vib)
            val inc = (f * dt).toDouble()

            var sample = 0f
            when (voice) {
                SynthVoice.HUM -> {
                    // Three partials, the upper two swelling with the wobble.
                    oscPhase = wrap(oscPhase + inc)
                    val p = twoPi * oscPhase
                    sample = sin(p).toFloat() * 0.55f +
                        sin(2.0 * p).toFloat() * (0.2f + 0.25f * wobble * trem) +
                        sin(3.0 * p).toFloat() * (0.1f + 0.15f * wobble * (1f - trem))
                    sample *= 0.8f + 0.2f * (1f - wobble) + 0.2f * wobble * trem
                    sample += wind.process(pink.next()) * grit * 1.5f
                }
                SynthVoice.SAW -> {
                    oscPhase = wrap(oscPhase + inc)
                    osc2Phase = wrap(osc2Phase + inc * (1.0 + 0.006 * (0.5f + wobble)))
                    sample = (saw(oscPhase.toFloat(), inc.toFloat()) + saw(osc2Phase.toFloat(), inc.toFloat())) * 0.3f
                    sample *= 1f - 0.25f * wobble * trem
                    sample += wind.process(pink.next()) * grit * 1.2f * (0.3f + 0.7f * load)
                    sample += grit * 0.06f * nextNoise() * load
                }
                SynthVoice.SQUARE -> {
                    oscPhase = wrap(oscPhase + inc)
                    subPhase = wrap(subPhase + inc * 0.5)
                    // The sub octave only once it is high enough for the speaker to play it.
                    val subAmt = 0.2f * ((f - 220f) / 300f).coerceIn(0f, 1f)
                    sample = square(oscPhase.toFloat(), inc.toFloat()) * 0.32f + sin(twoPi * subPhase).toFloat() * subAmt
                    sample *= 1f - 0.2f * wobble * trem
                    sample += wind.process(pink.next()) * grit
                }
                SynthVoice.TURBINE -> {
                    val pn = pink.next()
                    val air = rumble.process(pn) * (0.5f + 0.5f * n) * 1.6f + roar.process(pn) * (0.15f + 0.85f * n) * 1.3f
                    sample = air * (0.5f + grit)
                    // The shaft whine and its blade-pass partials, louder as it spools up.
                    val w = 0.06f + 0.3f * n
                    sample += whine(0, inc) * w +
                        whine(1, inc * 1.47) * w * 0.4f +
                        whine(2, inc * 2.0) * w * 0.35f +
                        whine(3, inc * 3.0) * w * 0.2f
                    sample += nextNoise() * 0.02f * n
                }
                SynthVoice.ELECTRIC -> {
                    // Motor whine, its inverter partials and a touch of saw for bite.
                    oscPhase = wrap(oscPhase + inc)
                    val w = 0.4f + 0.25f * n
                    sample = whine(0, inc) * w +
                        whine(1, inc * 2.0) * w * 0.35f +
                        whine(2, inc * 1.03) * w * 0.12f +
                        whine(3, inc * 4.0) * w * 0.08f +
                        saw(oscPhase.toFloat(), inc.toFloat()) * 0.12f
                    sample += wind.process(pink.next()) * (0.05f + 0.25f * n) * (0.4f + 0.6f * load)
                }
            }

            // Regen on the overrun: a thin whine a fifth up and some air.
            if (brakeAmt > 0.001f) {
                brakePhase = wrap(brakePhase + inc * 1.5)
                sample += brakeAmt * (sin(twoPi * brakePhase).toFloat() * 0.07f + nextNoise() * 0.02f)
            }
            if (popQueue.isNotEmpty()) sample += crackleBand.process(renderPops()) * 2f

            lpf1 += lpAlpha * (sample - lpf1)
            lpf2 += lpAlpha * (lpf1 - lpf2)
            val h1 = hpCoef * (hpf1Out + lpf2 - hpf1In); hpf1In = lpf2; hpf1Out = h1
            val h2 = hpCoef * (hpf2Out + h1 - hpf2In); hpf2In = h1; hpf2Out = h2
            val o = softClip(h2 * masterGain)
            out[outOffset + i] = o
            val a = abs(o)
            if (a > peak) peak = a
        }
        return peak
    }

    private fun whine(slot: Int, inc: Double): Float {
        whinePhases[slot] = wrap(whinePhases[slot] + inc)
        return sin(2.0 * PI * whinePhases[slot]).toFloat()
    }

    private fun wrap(p: Double): Double = if (p >= 1.0) p - 1.0 else p

    /** polyBLEP: the step's alias energy taken back out around the discontinuity. */
    private fun blep(t: Float, dtp: Float): Float = when {
        t < dtp -> { val x = t / dtp; x + x - x * x - 1f }
        t > 1f - dtp -> { val x = (t - 1f) / dtp; x * x + x + x + 1f }
        else -> 0f
    }

    private fun saw(t: Float, dtp: Float): Float = (2f * t - 1f) - blep(t, dtp)

    private fun square(t: Float, dtp: Float): Float {
        var v = if (t < 0.5f) 1f else -1f
        v += blep(t, dtp)
        var t2 = t + 0.5f
        if (t2 >= 1f) t2 -= 1f
        v -= blep(t2, dtp)
        return v
    }

    private fun onePoleAlpha(hz: Float): Float {
        val rc = 1f / (2f * PI.toFloat() * hz)
        return dt / (rc + dt)
    }

    /** Gentle knee: nothing past 0.97, the loud parts rounded rather than cut. */
    private fun softClip(x: Float): Float {
        if (x >= 3f) return 0.97f
        if (x <= -3f) return -0.97f
        val x2 = x * x
        return 0.97f * x * (27f + x2) / (27f + 9f * x2)
    }

    /** Renders the next sample of any active pops as a noise burst; the caller shapes it. */
    private fun renderPops(): Float {
        var sum = 0f
        val it = popQueue.iterator()
        while (it.hasNext()) {
            val pop = it.next()
            if (pop.remaining <= 0) { it.remove(); continue }
            val t = 1f - pop.remaining.toFloat() / pop.total
            sum += exp(-7f * t) * pop.gain * nextNoise()
            pop.remaining--
        }
        return sum
    }

    /** Reset all state, call when changing profile or after long silence. */
    fun reset() {
        schedule = null
        scheduleKey = null
        pulseAge = 1e9f
        pulseAmp = 0f
        oscPhase = 0.0; osc2Phase = 0.0; subPhase = 0.0; lfoPhase = 0.0; lfo2Phase = 0.0
        brakePhase = 0.0; gearPhase = 0.0
        spooledNorm = -1f
        whinePhases.fill(0.0)
        lpf1 = 0f; lpf2 = 0f
        hpf1In = 0f; hpf1Out = 0f; hpf2In = 0f; hpf2Out = 0f
        pipe1.reset(); pipe2.reset(); pipe3.reset(); bark.reset(); ring.reset(); intake.reset(); crackleBand.reset()
        rumble.reset(); roar.reset(); wind.reset()
        popQueue.clear()
    }

    private class Pop(var remaining: Int, val gain: Float = 1f) {
        val total: Int = remaining.coerceAtLeast(1)
    }

    private companion object {
        const val OUT_LEVEL = 0.9f
    }
}

/**
 * Where the cylinders fire in the cycle. [advance] moves the crank by a
 * fraction of a cycle and returns how many firings that step crossed.
 */
class FiringSchedule(angles: FloatArray, cycleDegrees: Float) {
    private val points: DoubleArray = angles.map { (it / cycleDegrees).toDouble().mod(1.0) }.sorted().toDoubleArray()
    /** Position in the cycle, 0..1. */
    private var phase: Double = 0.0
    /** The next point to fire; [points].size means the cycle's end comes first. */
    private var next: Int = 0

    fun advance(step: Float): Int {
        if (points.isEmpty() || step <= 0f) return 0
        var fired = 0
        var target = phase + step
        while (true) {
            if (next < points.size) {
                if (points[next] < target - EPS) { fired++; next++ } else break
            } else {
                if (1.0 < target - EPS) { target -= 1.0; next = 0 } else break
            }
        }
        phase = target
        return fired
    }

    private companion object { const val EPS = 1e-5 }
}

/** Chamberlin state-variable band-pass, the ring of a pipe or a formant. */
private class Resonator {
    private var low = 0f
    private var band = 0f
    private var f = 0.1f
    private var q = 1f

    fun set(hz: Float, quality: Float, sampleRate: Int) {
        f = (2f * sin((PI * hz / sampleRate).toFloat())).coerceAtMost(0.9f)
        q = 1f / quality.coerceAtLeast(0.5f)
    }

    fun process(x: Float): Float {
        low += f * band
        val high = x - low - q * band
        band += f * high
        return band
    }

    fun reset() { low = 0f; band = 0f }
}

/** Paul Kellet's economy pink noise, -1..1. */
private class PinkNoise {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var rng = 0x2F6E2B1
    fun next(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        val white = x.toFloat() / Int.MAX_VALUE.toFloat()
        b0 = 0.99765f * b0 + white * 0.0990460f
        b1 = 0.96300f * b1 + white * 0.2965164f
        b2 = 0.57000f * b2 + white * 1.0526913f
        return (b0 + b1 + b2 + white * 0.1848f) * 0.25f
    }
}
