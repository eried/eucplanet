package com.eried.eucplanet.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * DSP core. Owned by the audio thread — not thread-safe.
 *
 * Renders mono Float32 PCM at [sampleRate] driven by an [EngineParams] snapshot.
 * The synth carries its own state (crank phase, filter memory, decel-pop envelopes)
 * across [render] calls so audio is continuous across buffers.
 *
 * Two paths:
 *  - ICE: cylinder firing model — continuous fundamental at firingsPerSec, with
 *    a stack of harmonics, plus exhaust grit noise gated by load.
 *  - SYNTH: one oscillator + FM, frequency riding directly on RPM.
 *
 * Decel pops are queued as discrete one-shot envelopes and rendered on top.
 */
class EngineSynth(private val sampleRate: Int = 44100) {

    /** Crank phase 0..1 — at firing rate, wraps continuously. */
    private var firingPhase: Double = 0.0
    /** Slow exhaust-grit noise carrier phase (random-walked, not pure noise — adds bass rumble). */
    private var rumblePhase: Double = 0.0
    /** Synth-engine osc phase. */
    private var synthPhase: Double = 0.0
    /** Synth FM modulator phase. */
    private var fmPhase: Double = 0.0
    /** Engine-brake whine oscillator phase. */
    private var brakePhase: Double = 0.0

    /** Single-pole low-pass filter state for muffler. */
    private var lpfState: Float = 0f
    /** Single-pole high-pass (DC blocker) state. */
    private var hpfState: Float = 0f
    /** Previous input sample for HPF. */
    private var hpfPrev: Float = 0f

    /** Active decel-pop envelopes (each is a remaining-samples counter + start sample). */
    private val popQueue = ArrayDeque<PopEnvelope>(4)

    /** Per-sample dt. */
    private val dt: Float = 1f / sampleRate.toFloat()

    /** Cheap PRNG; java.util.Random allocates and locks — we use a simple xorshift. */
    private var rngState: Int = 0x1A2B3C4D

    private fun nextNoise(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        // Map to -1..1
        return (x.toFloat() / Int.MAX_VALUE.toFloat())
    }

    /**
     * Render [count] samples into [out] starting at [outOffset].
     * Returns the peak absolute amplitude in this buffer (for diagnostics).
     */
    fun render(params: EngineParams, out: FloatArray, outOffset: Int, count: Int): Float {
        // Queue any new pops from this update tick (caller passes pendingPops).
        repeat(params.pendingPops) {
            popQueue.addLast(PopEnvelope(remainingSamples = (sampleRate * 0.18f).toInt()))
        }

        val profile = params.profile
        val effRpm = (params.rpm + params.revBump).coerceAtLeast(0f)

        // Master amplitude curves
        val rpmRange = (profile.maxRpm - profile.idleRpm).coerceAtLeast(1)
        val rpmNorm = ((effRpm - profile.idleRpm) / rpmRange).coerceIn(0f, 1f)
        // Engine gets a bit louder at higher RPM/load, but not 1:1 — feels more natural at ~0.4..1.0
        val loadGain = 0.45f + 0.55f * (0.5f * rpmNorm + 0.5f * params.load)

        // Muffler LPF cutoff: open pipes = 6 kHz, muffled = 1.2 kHz. Lerp by mufflerOpenness.
        val cutoffHz = 1200f + (6000f - 1200f) * params.mufflerOpenness
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = dt / (rc + dt)

        val masterGain = params.masterGain * params.idleAmount * loadGain

        var peak = 0f

        if (profile.kind == EngineProfile.Kind.SYNTH) {
            renderSynth(profile, params, effRpm, rpmNorm, masterGain, alpha, out, outOffset, count).also {
                peak = it
            }
        } else {
            // ICE firing-model fundamental rate (cycles/sec):
            // 4-stroke: (rpm/60) * (cylinders/2)
            // 2-stroke: (rpm/60) * cylinders
            val revsPerSec = effRpm / 60f
            val firingsPerSec = if (profile.twoStroke) revsPerSec * profile.cylinderCount
                                else revsPerSec * (profile.cylinderCount / 2f)
            val firingRate = firingsPerSec.coerceAtLeast(0.5f)

            val rumbleHz = 30f + 20f * rpmNorm   // low rumble around 30-50 Hz
            val gritAmount = profile.exhaustGrit * (0.4f + 0.6f * params.load + 0.5f * params.decelAmount)

            // Engine-brake whine: high-frequency overtone above the firing rate, gated by engineBrakeAmount.
            // 2-strokes get a ringier brake (higher overtone) than 4-strokes.
            val brakeAmt = params.engineBrakeAmount
            val brakeMult = if (profile.twoStroke) 5.5f else 3.5f
            val brakeHz = (firingRate * brakeMult).coerceIn(60f, 4000f)

            // Pre-extract harmonics into locals so the hot loop is allocation-free.
            val h = profile.harmonics
            val h1 = if (h.isNotEmpty()) h[0] else 1f
            val h2 = if (h.size > 1) h[1] else 0f
            val h3 = if (h.size > 2) h[2] else 0f
            val h4 = if (h.size > 3) h[3] else 0f
            val h5 = if (h.size > 4) h[4] else 0f
            val h6 = if (h.size > 5) h[5] else 0f
            // Harmonic-sum normalization keeps overall loudness sane regardless of profile.
            val hSum = (h1 + h2 + h3 + h4 + h5 + h6).coerceAtLeast(0.0001f)
            val hNorm = 1f / hSum

            val twoPi = 2.0 * PI
            for (i in 0 until count) {
                // Advance phases
                firingPhase += firingRate * dt
                if (firingPhase >= 1.0) firingPhase -= firingPhase.toInt().toDouble()
                rumblePhase += rumbleHz * dt
                if (rumblePhase >= 1.0) rumblePhase -= rumblePhase.toInt().toDouble()
                brakePhase += brakeHz * dt
                if (brakePhase >= 1.0) brakePhase -= brakePhase.toInt().toDouble()

                val ph = twoPi * firingPhase
                // Harmonic stack
                var sample = (
                    h1 * sin(ph) +
                    h2 * sin(2.0 * ph) +
                    h3 * sin(3.0 * ph) +
                    h4 * sin(4.0 * ph) +
                    h5 * sin(5.0 * ph) +
                    h6 * sin(6.0 * ph)
                ).toFloat() * hNorm

                // Compression "thump" — a sharp positive spike at firing crossing
                if (profile.compressionTone > 0f) {
                    val thump = pulseShape(firingPhase.toFloat()) * profile.compressionTone
                    sample += thump * 0.45f
                }

                // Sub rumble layer
                sample += 0.18f * sin(twoPi * rumblePhase).toFloat() * (0.4f + 0.6f * params.load)

                // Noise grit
                sample += gritAmount * 0.6f * nextNoise()

                // Decel pop overlay
                if (popQueue.isNotEmpty()) {
                    sample += renderPops(profile)
                }

                // Engine-brake whine — high-frequency overtone + a touch of airy noise during overrun.
                if (brakeAmt > 0.001f) {
                    val whine = sin(twoPi * brakePhase).toFloat() * 0.18f
                    val airy = nextNoise() * 0.04f
                    sample += brakeAmt * (whine + airy)
                }

                // Muffler LPF
                lpfState += alpha * (sample - lpfState)

                // DC blocker
                val hp = lpfState - hpfPrev + 0.995f * hpfState
                hpfPrev = lpfState
                hpfState = hp

                val outSample = hp * masterGain
                out[outOffset + i] = outSample
                val abs = if (outSample >= 0f) outSample else -outSample
                if (abs > peak) peak = abs
            }
        }

        return peak
    }

    private fun renderSynth(
        profile: EngineProfile,
        params: EngineParams,
        effRpm: Float,
        rpmNorm: Float,
        masterGain: Float,
        alpha: Float,
        out: FloatArray,
        outOffset: Int,
        count: Int
    ): Float {
        var peak = 0f
        val freq = profile.synthBaseHz + (profile.synthMaxHz - profile.synthBaseHz) * rpmNorm
        val fmFreq = freq * 0.5f
        val fmDepth = profile.synthFmDepth * (0.5f + 0.5f * params.load) * freq
        val twoPi = 2.0 * PI
        val shape = profile.synthShape

        for (i in 0 until count) {
            fmPhase += fmFreq * dt
            if (fmPhase >= 1.0) fmPhase -= fmPhase.toInt().toDouble()
            val modulator = sin(twoPi * fmPhase).toFloat()
            val instFreq = (freq + modulator * fmDepth).coerceAtLeast(20f)

            synthPhase += instFreq * dt
            if (synthPhase >= 1.0) synthPhase -= synthPhase.toInt().toDouble()

            val p = synthPhase.toFloat()
            val raw = when (shape) {
                0 -> sin(twoPi * p).toFloat()                       // sine
                1 -> 2f * p - 1f                                    // saw
                else -> if (p < 0.5f) 1f else -1f                   // square
            }
            // Anti-alias the saw/square a touch by softening with cubic shaper
            val osc = raw - raw * raw * raw * 0.3f
            // Add grit
            val gritted = osc + profile.exhaustGrit * 0.5f * nextNoise()

            lpfState += alpha * (gritted - lpfState)
            val hp = lpfState - hpfPrev + 0.995f * hpfState
            hpfPrev = lpfState
            hpfState = hp

            val s = hp * masterGain * 0.7f
            out[outOffset + i] = s
            val abs = if (s >= 0f) s else -s
            if (abs > peak) peak = abs
        }
        return peak
    }

    /**
     * Pulse shape: sharp leading edge, exponential decay over the firing cycle.
     * Phase 0..1 maps to one firing cycle.
     */
    private fun pulseShape(phase: Float): Float {
        // Decay constant tuned so most energy is in the first 1/4 of the cycle
        return exp(-6f * phase) - 0.1f
    }

    /** Renders the next sample of any active decel pops, ramping their envelopes down. */
    private fun renderPops(profile: EngineProfile): Float {
        var sum = 0f
        val it = popQueue.iterator()
        while (it.hasNext()) {
            val pop = it.next()
            if (pop.remainingSamples <= 0) {
                it.remove()
                continue
            }
            val total = (sampleRate * 0.18f).toInt()
            val t = 1f - (pop.remainingSamples.toFloat() / total)   // 0..1 over life
            // Sharp attack + exponential decay, fat noise burst with low-frequency emphasis
            val env = exp(-8f * t)
            sum += env * 0.85f * nextNoise() * (0.6f + 0.6f * profile.decelPopProbability)
            pop.remainingSamples--
        }
        return sum
    }

    /** Reset all state — call when changing profile or after long silence. */
    fun reset() {
        firingPhase = 0.0
        rumblePhase = 0.0
        synthPhase = 0.0
        fmPhase = 0.0
        brakePhase = 0.0
        lpfState = 0f
        hpfState = 0f
        hpfPrev = 0f
        popQueue.clear()
    }

    private class PopEnvelope(var remainingSamples: Int)
}
