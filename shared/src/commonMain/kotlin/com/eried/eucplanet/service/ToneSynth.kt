package com.eried.eucplanet.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The sample math behind every app tone: alarm beeps, the voice cues and the
 * Beep Studio's live stream. 16-bit mono at [SAMPLE_RATE]. Each platform's
 * player (TonePlayer on Android) only hands these samples to its audio output,
 * so a beep is the same waveform on both phones.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44100
    private const val TWO_PI = 2.0 * PI
    private const val FM_INDEX = 3.0       // FM modulation index (modulator runs at 2x carrier)
    private const val DRIVE = 3.0          // waveshaper drive for the "Drive" effect
    private const val SWEEP_RATIO = 3.0    // "Sweep" low-pass cutoff = current pitch * this
    private const val SWEEP_Q = 0.4        // filter damping; lower = more resonance
    private const val CRUSH_LEVELS = 8.0   // "Crush" quantization: round(x*L)/L, ~4-bit

    private const val sampleRate = SAMPLE_RATE

    /**
     * One oscillator sample for [waveform] at carrier phase [ph] (cycles, 0..1);
     * [modPh] is the FM modulator phase (used only by waveform 4). Codes match
     * [com.eried.eucplanet.data.model.AlarmRule.beepWaveform]: 0=sine, 1=triangle,
     * 2=square, 3=saw, 4=FM.
     */
    fun waveSample(waveform: Int, ph: Double, modPh: Double): Double {
        val f = ph - kotlin.math.floor(ph)
        return when (waveform) {
            1 -> 4.0 * kotlin.math.abs(f - 0.5) - 1.0                 // triangle
            2 -> if (f < 0.5) 1.0 else -1.0                          // square
            3 -> 2.0 * f - 1.0                                       // saw
            4 -> sin(TWO_PI * ph + FM_INDEX * sin(TWO_PI * modPh))   // FM (metallic)
            else -> sin(TWO_PI * ph)                                 // sine
        }
    }

    /** Mutable state for the stateful effects (filter + sample-hold). */
    class Fx { var lo = 0.0; var band = 0.0; var held = 0.0; var ctr = 0 }

    /**
     * Apply [effect] to sample [x] at the current pitch [freq] (Hz), mutating [fx]
     * for the stateful ones. Codes match
     * [com.eried.eucplanet.data.model.AlarmRule.beepEffect]: 0=none, 1=drive,
     * 2=sweep (resonant low-pass tracking pitch), 3=crush (bitcrush).
     */
    fun applyFx(effect: Int, x: Double, freq: Double, fx: Fx): Double = when (effect) {
        1 -> kotlin.math.tanh(DRIVE * x)
        2 -> {
            val fc = (freq * SWEEP_RATIO).coerceIn(300.0, 8000.0)
            val f = 2.0 * sin(PI * fc / sampleRate)
            fx.lo += f * fx.band
            val hi = x - fx.lo - SWEEP_Q * fx.band
            fx.band += f * hi
            fx.lo
        }
        3 -> {
            val hold = maxOf(1, sampleRate / 6000)
            // floor(v + 0.5) is java.lang.Math.round's rounding, kept so the
            // crushed levels do not move.
            if (fx.ctr <= 0) { fx.held = floor(x * CRUSH_LEVELS + 0.5) / CRUSH_LEVELS; fx.ctr = hold }
            fx.ctr--
            fx.held
        }
        else -> x
    }

    /**
     * A finished beep pattern: [samples] to write and [frames] to wait for,
     * padded to at least [minFrames] (an output's start threshold).
     */
    class Rendered(val samples: ShortArray, val frames: Int)

    /**
     * [count] beeps of [durationMs] at [frequencyHz], [gapMs] apart, as one
     * buffer. See TonePlayer.playBeep for what each parameter does to the
     * sound. Null when the tone would be empty.
     */
    fun renderBeep(
        frequencyHz: Int,
        durationMs: Int,
        count: Int,
        gapMs: Int,
        volumePct: Int,
        transitionMs: Int,
        waveform: Int,
        effect: Int,
        glideToHz: Int,
        leadPadMs: Int,
        minFrames: Int,
    ): Rendered? {
        val toneN = (sampleRate.toLong() * durationMs / 1000).toInt()
        if (toneN <= 0) return null
        val gapN = (sampleRate.toLong() * gapMs.coerceAtLeast(0) / 1000).toInt()
        // gap 0 merges the `count` beeps into ONE continuous run of duration*count
        // (a single longer tone: "3 beeps at gap 0" = "3x duration"), consistent
        // across alarms. gap>0 keeps them as `count` separate runs. The raised-cosine
        // attack/release is applied per RUN, so gap 0 ramps up once and down once
        // instead of undulating.
        val gapless = gapN <= 0
        val runN = if (gapless) toneN * count else toneN
        val runCount = if (gapless) 1 else count
        // Lead-in silence so the audio route/amp power-up transient (a start-of-
        // playback pop) settles in silence before the tone ramps in; a small tail
        // pad drains cleanly before stop().
        val leadPadN = sampleRate * leadPadMs.coerceAtLeast(0) / 1000
        val tailPadN = sampleRate * 12 / 1000
        val bodyN = leadPadN + runN * runCount + gapN * (runCount - 1).coerceAtLeast(0) + tailPadN
        val totalN = maxOf(bodyN, minFrames)
        val samples = ShortArray(totalN)
        // 0.8 = app-side headroom; volumePct scales under the system media
        // volume ceiling (the platform applies that on top, so we can't exceed it).
        val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
        // Raised-cosine ramp length (attack/release) from transitionMs; auto (< 0)
        // = half the base beep. Capped to half the run so it always fits.
        val edge = (if (transitionMs >= 0)
            (sampleRate.toLong() * transitionMs / 1000).toInt()
        else toneN / 2).coerceIn(1, (runN / 2).coerceAtLeast(1))

        var w = leadPadN  // write cursor; leading silence pad already skipped
        val inc = frequencyHz.toDouble() / sampleRate
        val glideEnd = if (glideToHz > 0) glideToHz.toDouble() else frequencyHz.toDouble()
        var phase = 0.0       // carrier phase in cycles; continuous within a run
        var modPhase = 0.0    // FM modulator phase (runs at 2x carrier)
        val fx = Fx()
        for (b in 0 until runCount) {
            for (i in 0 until runN) {
                // Raised-cosine attack over the first [edge] of the run and release
                // over the last [edge]; flat sustain (env = 1) in between.
                val ramp = minOf(
                    if (i < edge) i.toDouble() / edge else 1.0,
                    if (i >= runN - edge) (runN - i).toDouble() / edge else 1.0
                )
                val env = 0.5 - 0.5 * cos(PI * ramp)
                // Steady unless gliding, in which case the frequency walks
                // from one end to the other across the run.
                val freqNow = if (glideToHz > 0) {
                    frequencyHz + (glideEnd - frequencyHz) * (i.toDouble() / runN)
                } else {
                    frequencyHz.toDouble()
                }
                val incNow = if (glideToHz > 0) freqNow / sampleRate else inc
                val osc = applyFx(effect, waveSample(waveform, phase, modPhase), freqNow, fx)
                samples[w++] = (osc * env * gain * Short.MAX_VALUE).toInt().toShort()
                phase += incNow; if (phase >= 1.0) phase -= 1.0
                modPhase += 2.0 * incNow; if (modPhase >= 1.0) modPhase -= 1.0
            }
            if (b < runCount - 1 && gapN > 0) {
                w += gapN   // leave zeros (silence); restart phase + effect state after the gap
                phase = 0.0; modPhase = 0.0
                fx.lo = 0.0; fx.band = 0.0; fx.ctr = 0
            }
        }
        return Rendered(samples, totalN)
    }

    /**
     * Several notes in one buffer, each with its own short ramp and [gapMs]
     * of silence between. See TonePlayer.playNotes. Null when a note is empty.
     */
    fun renderNotes(
        notes: List<Pair<Int, Int>>,
        gapMs: Int,
        leadPadMs: Int,
        volumePct: Int,
        minFrames: Int,
    ): Rendered? {
        val leadPadN = sampleRate * leadPadMs.coerceAtLeast(0) / 1000
        val tailPadN = sampleRate * 12 / 1000
        val gapN = sampleRate * gapMs.coerceAtLeast(0) / 1000
        val noteN = notes.map { (_, ms) -> (sampleRate.toLong() * ms / 1000).toInt() }
        if (noteN.any { it <= 0 }) return null
        val bodyN = leadPadN + noteN.sum() + gapN * (notes.size - 1) + tailPadN
        val totalN = maxOf(bodyN, minFrames)
        val samples = ShortArray(totalN)
        val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)

        var w = leadPadN
        for ((index, note) in notes.withIndex()) {
            val (hz, _) = note
            val n = noteN[index]
            // A quarter of the note, capped, so short notes still ramp and
            // long ones are not all ramp.
            val edge = (n / 4).coerceIn(1, maxOf(1, sampleRate * 12 / 1000))
            var phase = 0.0
            val inc = hz.toDouble() / sampleRate
            for (i in 0 until n) {
                val ramp = minOf(
                    if (i < edge) i.toDouble() / edge else 1.0,
                    if (i >= n - edge) (n - i).toDouble() / edge else 1.0
                )
                val env = 0.5 - 0.5 * cos(PI * ramp)
                samples[w++] = (sin(2.0 * PI * phase) * env * gain * Short.MAX_VALUE)
                    .toInt().toShort()
                phase += inc
                if (phase >= 1.0) phase -= 1.0
            }
            if (index < notes.size - 1) w += gapN
        }
        return Rendered(samples, totalN)
    }

    /**
     * The Beep Studio's endless tone, one buffer at a time. Its phase and
     * effect state carry across buffers, so a pitch change between two
     * [fill] calls is a glide rather than a click.
     */
    class Stream {
        private var phase = 0.0   // carrier phase in cycles; continuous across buffers
        private var modPhase = 0.0 // FM modulator phase (runs at 2x carrier)
        private val fx = Fx()      // sweep filter + crush sample-hold, persists across buffers
        private var pos = 0       // sample index within the current on+gap period
        private var emitted = 0L  // total samples emitted (drives the gap-0 fade-in)

        fun fill(
            buf: ShortArray, frequencyHz: Int, durationMs: Int, gapMs: Int, volumePct: Int,
            transitionMs: Int, waveform: Int, effect: Int,
        ) {
            val bufFrames = buf.size
            val inc = frequencyHz.toDouble() / sampleRate
            val durN = (sampleRate.toLong() * durationMs / 1000).toInt().coerceAtLeast(1)
            val gapN = (sampleRate.toLong() * gapMs / 1000).toInt().coerceAtLeast(0)
            val periodN = durN + gapN
            val gain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
            val edge = (if (transitionMs >= 0)
                (sampleRate.toLong() * transitionMs / 1000).toInt()
            else durN / 2).coerceIn(1, (durN / 2).coerceAtLeast(1))
            val gapless = gapN <= 0
            for (k in 0 until bufFrames) {
                if (pos >= periodN) pos = 0
                val env = if (gapless) {
                    // gap 0 = one steady continuous tone; fade in once at the start.
                    if (emitted < edge) 0.5 - 0.5 * cos(PI * emitted.toDouble() / edge) else 1.0
                } else if (pos < durN) {
                    val ramp = minOf(
                        if (pos < edge) pos.toDouble() / edge else 1.0,
                        if (pos >= durN - edge) (durN - pos).toDouble() / edge else 1.0
                    )
                    0.5 - 0.5 * cos(PI * ramp)
                } else 0.0   // in the gap
                val osc = applyFx(effect, waveSample(waveform, phase, modPhase), frequencyHz.toDouble(), fx)
                val s = osc * env * gain
                buf[k] = (s * Short.MAX_VALUE).toInt().toShort()
                phase += inc
                if (phase >= 1.0) phase -= 1.0
                modPhase += 2.0 * inc
                if (modPhase >= 1.0) modPhase -= 1.0
                pos++
                emitted++
            }
        }

        /** About 8 ms fading to silence, so stopping does not click. */
        fun fadeOut(frequencyHz: Int, volumePct: Int): ShortArray {
            val fadeN = sampleRate * 8 / 1000
            val fadeBuf = ShortArray(fadeN)
            val fadeInc = frequencyHz.toDouble() / sampleRate
            val fadeGain = 0.8 * (volumePct.coerceIn(0, 100) / 100.0)
            for (k in 0 until fadeN) {
                val env = 0.5 + 0.5 * cos(PI * k.toDouble() / fadeN)   // 1 -> 0
                fadeBuf[k] = (sin(2.0 * PI * phase) * env * fadeGain * Short.MAX_VALUE).toInt().toShort()
                phase += fadeInc
                if (phase >= 1.0) phase -= 1.0
            }
            return fadeBuf
        }
    }
}
