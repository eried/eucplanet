package com.eried.eucplanet.audio

import kotlin.test.assertTrue
import com.eried.eucplanet.platform.format
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
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
        val synth = EngineSynth(sr, TestGrainBanks.loader(sr))
        val rpm = profile.idleRpm + (profile.maxRpm - profile.idleRpm) * rpmFrac
        val p = EngineParams(rpm = rpm, load = load, idleAmount = 1f, masterGain = 1f, profile = profile, engineBrakeAmount = brake)
        val out = FloatArray(n)
        // Warm up so resonators and envelopes settle and a recorded engine's start is over.
        val warm = FloatArray(4096)
        repeat(40) { synth.render(p, warm, 0, warm.size) }
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

    /** Every synthesised engine, and each recorded bank once (the hidden old keys share banks with listed engines). */
    private val procedural = EngineProfile.PROFILES.filter { p ->
        p.grainAsset == null || EngineProfile.PROFILES.first { it.grainAsset == p.grainAsset } === p
    }
    private val EngineProfile.recorded: Boolean get() = grainAsset != null

    private fun check(all: (MutableList<String>) -> Unit) {
        val misses = mutableListOf<String>()
        all(misses)
        assertTrue(misses.isEmpty(), misses.joinToString("\n", prefix = "\n"))
    }

    /**
     * A recording keeps its real low end for headphones, with the low end's rhythm
     * copied into the speaker band by the bank tool (speakerLift), so its bar is looser:
     * a real V8 idles at 46 Hz and no lift should pretend otherwise.
     */
    @Test
    fun `at idle most of the sound is where a phone speaker plays`() = check { misses ->
        for (p in procedural) {
            val sub = share(power(render(p, 0f, 0.05f)), 0.0, 180.0)
            val bar = if (p.recorded) 0.6 else 0.45
            if (sub >= bar) misses += "${p.key}: ${(sub * 100).toInt()}% of the idle is under 180 Hz"
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

    /**
     * A steam engine chuffs faster when it runs, it does not change pitch; every other
     * synthesised engine climbs. A recorded engine keeps its real pipes whatever the
     * revs, so its weight stays where the recording put it; that its firing rate
     * follows the wheel is checked in [GrainBankTest].
     */
    @Test
    fun `revving moves the sound up`() = check { misses ->
        for (p in procedural.filter { it.key != "STEAM_LOCO" && !it.recorded }) {
            val lo = centroid(power(render(p, 0.1f, 0.3f)))
            val hi = centroid(power(render(p, 0.9f, 0.9f)))
            if (hi <= lo * 1.15) misses += "${p.key}: ${lo.toInt()} Hz at low revs, ${hi.toInt()} Hz at high"
        }
    }

    /**
     * A tester heard some engines turn into a screech under acceleration. A
     * piston engine's weight stays in the low mids when it pulls, unless its
     * firing rate itself is up there (a V12 at 8500 rpm fires 850 times a
     * second); the synth whines may climb, but not out of the comfortable band.
     */
    @Test
    fun `a hard pull does not turn into a screech`() = check { misses ->
        for (p in procedural) {
            val c = centroid(power(render(p, 0.9f, 1f)))
            val rpm = p.idleRpm + (p.maxRpm - p.idleRpm) * 0.9
            val firingRate = rpm / 60.0 * (360.0 / p.cycleDegrees) * p.cylinderCount
            val limit = when {
                p.recorded -> 2200.0   // real air and real valves, as recorded
                p.kind == EngineProfile.Kind.SYNTH -> 1900.0
                p.key == "STEAM_LOCO" -> 1300.0   // a hiss of steam, by design
                // A deliberate high ring (a 2-stroke's chamber) is allowed to lift it a little.
                else -> maxOf(1000.0, firingRate * 1.3) + 600.0 * p.ringAmount
            }
            if (c > limit) misses += "${p.key}: centroid ${c.toInt()} Hz on a full pull (limit ${limit.toInt()})"
        }
    }

    /**
     * Distinct partials between [loHz] and [hiHz]: the spectrum smoothed to about
     * 6 Hz, then every bump that stands 6 dB above the dip on both sides within
     * 25 Hz and within 36 dB of the loudest bin. A rich engine has many.
     */
    private fun partials(pw: DoubleArray, loHz: Double, hiHz: Double): Int {
        val binHz = sr.toDouble() / n
        val sm = DoubleArray(pw.size)
        val w = 4
        for (i in pw.indices) {
            var s = 0.0; var c = 0
            for (k in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(pw.size - 1)) { s += pw[k]; c++ }
            sm[i] = s / c
        }
        val lo = (loHz / binHz).toInt(); val hi = (hiHz / binHz).toInt().coerceAtMost(sm.size - 1)
        var top = 0.0
        for (i in lo..hi) if (sm[i] > top) top = sm[i]
        val floor = top * 10.0.pow(-3.6)
        val reach = (25.0 / binHz).toInt()
        var count = 0
        var i = lo
        while (i <= hi) {
            if (sm[i] >= floor && sm[i] >= sm[i - 1] && sm[i] > sm[i + 1]) {
                var dipL = sm[i]; var dipR = sm[i]
                for (k in (i - reach).coerceAtLeast(1) until i) if (sm[k] < dipL) dipL = sm[k]
                for (k in i + 1..(i + reach).coerceAtMost(sm.size - 2)) if (sm[k] < dipR) dipR = sm[k]
                if (sm[i] > dipL * 4 && sm[i] > dipR * 4) { count++; i += reach / 2 }
            }
            i++
        }
        return count
    }

    /** Richness: a synthesised piston engine under load is many partials, not one note with air around it. */
    @Test
    fun `a piston engine under load has many partials`() = check { misses ->
        val counts = mutableListOf<String>()
        var miss = false
        for (p in procedural.filter { it.kind == EngineProfile.Kind.ICE && it.key != "STEAM_LOCO" && !it.recorded }) {
            val c = partials(power(render(p, 0.5f, 0.8f)), 150.0, 1500.0)
            counts += "${p.key}=$c"
            if (c < 8) miss = true
        }
        if (miss) misses += "fewer than 8 partials in 150-1500 Hz for: " + counts.joinToString(", ")
    }

    /**
     * How much of the 150 to 4000 Hz band sits within 40 Hz of the [k] strongest
     * lines. A whine is one or two lines and nothing else; a combustion roar
     * spreads its energy over many harmonics and the noise between them.
     */
    private fun lineShare(pw: DoubleArray, k: Int, loHz: Double, hiHz: Double): Double {
        val binHz = sr.toDouble() / n
        val lo = (loHz / binHz).toInt(); val hi = (hiHz / binHz).toInt().coerceAtMost(pw.size - 2)
        val peaks = mutableListOf<Pair<Double, Int>>()
        for (i in lo..hi) if (pw[i] > pw[i - 1] && pw[i] >= pw[i + 1]) peaks += pw[i] to i
        peaks.sortByDescending { it.first }
        val picked = mutableListOf<Int>()
        val apart = (60.0 / binHz).toInt()
        for ((_, i) in peaks) {
            if (picked.all { abs(it - i) > apart }) picked += i
            if (picked.size == k) break
        }
        val reach = (40.0 / binHz).toInt()
        var inLines = 0.0; var total = 0.0
        for (i in lo..hi) {
            total += pw[i]
            if (picked.any { abs(it - i) <= reach }) inLines += pw[i]
        }
        return inLines / total.coerceAtLeast(1e-12)
    }

    /**
     * A tester heard the multi-cylinder engines turn into an electronic whine on
     * acceleration while the single stayed an engine. At 6000 rpm a V8 fires 400
     * times a second: if each pulse is squeezed to fit the gap, the train is a
     * sine wave. Before the fix the V8 put 83% of its band into three lines and
     * the single 29%; flat out, no piston engine may now put more than 55% there.
     * The turbines (jet, pod racer, hover bike) are air with a whine on top and
     * are held to the same bar; the hums and the Tron square are whines by design.
     * The recorded engines are exempt: a real Cobra at idle puts 95% of this band
     * into its three lowest harmonics and nobody calls it a whine, so the bar is a
     * synthesiser's bar. Their rate following the wheel is pinned in [GrainBankTest].
     */
    @Test
    fun `flat out - a piston engine is a roar and not a whine`() = check { misses ->
        val report = mutableListOf<String>()
        for (p in procedural.filter { !it.recorded && (it.kind == EngineProfile.Kind.ICE || it.voice == SynthVoice.TURBINE) }) {
            val s = lineShare(power(render(p, 0.9f, 1f)), 3, 150.0, 4000.0)
            report += "${p.key}=${"%.2f".format(s)}"
            if (s > 0.55) misses += "${p.key}: ${(s * 100).toInt()}% of the band is three lines"
        }
        println("line share flat out: " + report.joinToString(", "))
    }

    @Test
    fun `the engine brake is heard on the overrun`() {
        val p = EngineProfile.byKey("FOUR_STROKE_SINGLE")
        val plain = render(p, 0.4f, 0.05f, brake = 0f)
        val braking = render(p, 0.4f, 0.05f, brake = 1f)
        val plainTop = share(power(plain), 1500.0, 6000.0)
        val brakeTop = share(power(braking), 1500.0, 6000.0)
        assertTrue(brakeTop > plainTop * 1.5, "overrun adds nothing above 1.5 kHz ($plainTop vs $brakeTop)")
    }

    /**
     * A tester heard the throttle chop and the coast of the recorded engines as
     * noise, and they were: every pop played as a naked burst of white noise (twice
     * for a bank with no backfire of its own) and the overrun laid a 1.5 kHz sine and a
     * hiss over the recording. Now the overrun is the engine quieter and duller with
     * a faint gear whine, and a pop is the recording's own backfire, a toot of the
     * steam whistle, or a bang through a pipe. Neither may add hiss above 1.5 kHz.
     */
    @Test
    fun `the overrun and the pops of a recorded engine are not hiss`() = check { misses ->
        for (p in procedural.filter { it.recorded }) {
            val pulled = share(power(render(p, 0.6f, 0.9f)), 1500.0, sr / 2.0)
            val coasting = share(power(render(p, 0.6f, 0f, brake = 1f)), 1500.0, sr / 2.0)
            if (coasting > pulled + 0.05) misses += "${p.key}: ${(coasting * 100).toInt()}% above 1.5 kHz on the overrun, ${(pulled * 100).toInt()}% under power"
            val synth = EngineSynth(sr, TestGrainBanks.loader(sr))
            val rpm = p.idleRpm + (p.maxRpm - p.idleRpm) * 0.6f
            val quiet = EngineParams(rpm = rpm, load = 0f, idleAmount = 1f, masterGain = 1f, profile = p)
            val warm = FloatArray(4096)
            repeat(40) { synth.render(quiet, warm, 0, warm.size) }
            val out = FloatArray(n)
            synth.render(quiet.copy(pendingPops = 3), out, 0, n)
            val popped = share(power(out), 1500.0, sr / 2.0)
            if (popped > pulled + 0.10) misses += "${p.key}: ${(popped * 100).toInt()}% above 1.5 kHz with three pops, ${(pulled * 100).toInt()}% under power"
        }
    }
}
