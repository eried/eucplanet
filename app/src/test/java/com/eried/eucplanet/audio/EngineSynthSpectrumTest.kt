package com.eried.eucplanet.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the rider's phone can play. A phone speaker has nothing under about
 * 200 Hz, so an engine whose energy sits there is a hum with ticks; a raw
 * square wave aliases into noise at the top. These pin the spectrum of every
 * procedural engine at idle and under a hard pull. Each test reports every
 * engine that misses, not only the first.
 */
class EngineSynthSpectrumTest {

    private val sr = 44100
    private val n = 1 shl 16   // 1.5 s

    private fun render(profile: EngineProfile, rpmFrac: Float, load: Float, brake: Float = 0f): FloatArray {
        val synth = EngineSynth(sr)
        val rpm = profile.idleRpm + (profile.maxRpm - profile.idleRpm) * rpmFrac
        val p = EngineParams(rpm = rpm, load = load, idleAmount = 1f, masterGain = 1f, profile = profile, engineBrakeAmount = brake)
        val out = FloatArray(n)
        // Warm up so resonators and envelopes settle.
        val warm = FloatArray(4096)
        repeat(12) { synth.render(p, warm, 0, warm.size) }
        synth.render(p, out, 0, n)
        return out
    }

    /** Power spectrum by a plain radix-2 FFT. */
    private fun power(x: FloatArray): DoubleArray {
        val re = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        return DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun share(p: DoubleArray, loHz: Double, hiHz: Double): Double {
        val lo = (loHz * n / sr).toInt(); val hi = (hiHz * n / sr).toInt().coerceAtMost(p.size)
        var s = 0.0; var t = 0.0
        for (i in p.indices) { if (i in lo until hi) s += p[i]; t += p[i] }
        return s / t.coerceAtLeast(1e-12)
    }

    private fun centroid(pw: DoubleArray): Double {
        var s = 0.0; var t = 0.0
        for (i in pw.indices) { s += pw[i] * i * sr.toDouble() / n; t += pw[i] }
        return s / t.coerceAtLeast(1e-12)
    }

    private fun db(x: FloatArray) = 20 * log10(sqrt(x.sumOf { (it * it).toDouble() } / x.size).coerceAtLeast(1e-9))

    private val procedural = EngineProfile.PROFILES.filter { it.sampleAssetBase == null }

    private fun check(all: (MutableList<String>) -> Unit) {
        val misses = mutableListOf<String>()
        all(misses)
        assertTrue(misses.joinToString("\n", prefix = "\n"), misses.isEmpty())
    }

    @Test
    fun `at idle most of the sound is where a phone speaker plays`() = check { misses ->
        for (p in procedural) {
            val sub = share(power(render(p, 0f, 0.05f)), 0.0, 180.0)
            if (sub >= 0.45) misses += "${p.key}: ${(sub * 100).toInt()}% of the idle is under 180 Hz"
        }
    }

    @Test
    fun `under a hard pull the sound sits in the speaker band and does not hiss`() = check { misses ->
        for (p in procedural) {
            val pw = power(render(p, 0.8f, 0.9f))
            val sub = share(pw, 0.0, 150.0)
            val top = share(pw, 8000.0, sr / 2.0)
            if (sub >= 0.35) misses += "${p.key}: ${(sub * 100).toInt()}% of the pull is under 150 Hz"
            if (top >= 0.08) misses += "${p.key}: ${(top * 100).toInt()}% of the pull is above 8 kHz"
        }
    }

    @Test
    fun `nothing clips and the engines sit at a similar loudness`() = check { misses ->
        for (p in procedural) {
            val pull = render(p, 0.8f, 0.9f)
            val idle = render(p, 0f, 0.05f)
            if (pull.maxOf { abs(it) } > 0.98f) misses += "${p.key} clips"
            val pullDb = db(pull)
            val idleDb = db(idle)
            if (pullDb !in -22.0..-8.0) misses += "${p.key} pull is ${"%.1f".format(pullDb)} dB"
            if (idleDb !in -32.0..-12.0) misses += "${p.key} idle is ${"%.1f".format(idleDb)} dB"
        }
    }

    @Test
    fun `revving moves the sound up`() = check { misses ->
        for (p in procedural) {
            val lo = centroid(power(render(p, 0.1f, 0.3f)))
            val hi = centroid(power(render(p, 0.9f, 0.9f)))
            if (hi <= lo * 1.3) misses += "${p.key}: ${lo.toInt()} Hz at low revs, ${hi.toInt()} Hz at high"
        }
    }

    @Test
    fun `the engine brake is heard on the overrun`() {
        val p = EngineProfile.byKey("FOUR_STROKE_SINGLE")
        val plain = render(p, 0.4f, 0.05f, brake = 0f)
        val braking = render(p, 0.4f, 0.05f, brake = 1f)
        val plainTop = share(power(plain), 1500.0, 6000.0)
        val brakeTop = share(power(braking), 1500.0, 6000.0)
        assertTrue("overrun adds nothing above 1.5 kHz ($plainTop vs $brakeTop)", brakeTop > plainTop * 1.5)
    }
}
