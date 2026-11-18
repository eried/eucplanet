package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The recorded engines: every bank the catalog names ships and reads, and the
 * player lays its grains at the rate the wheel asks for.
 */
class GrainBankTest {

    private val sr = 44100
    private val recorded = EngineProfile.PROFILES.filter { it.grainAsset != null }

    @Test
    fun `every recorded engine has a bank that reads`() {
        val missing = ArrayList<String>()
        for (p in recorded) {
            val f = File(TestGrainBanks.dir, "${p.grainAsset}.bin")
            if (!f.exists()) { missing += "${p.key}: ${f.name} missing"; continue }
            val bank = GrainBank.load(sr) { f.inputStream() }
            if (bank == null) { missing += "${p.key}: ${f.name} does not read"; continue }
            if (bank.bands.zipWithNext().any { (a, b) -> a.hz > b.hz }) missing += "${p.key}: bands out of order"
            if (bank.bands.any { b -> b.runs.isEmpty() || b.runs.any { it.isEmpty() } }) missing += "${p.key}: an empty band"
            if (bank.lowestHz <= 0f) missing += "${p.key}: no idle rate"
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `no stray bank without an engine`() {
        val named = recorded.map { "${it.grainAsset}.bin" }.toSet()
        val stray = (TestGrainBanks.dir.listFiles { f -> f.name.startsWith("grain_") && f.name.endsWith(".bin") } ?: emptyArray())
            .map { it.name }.filter { it !in named }
        assertTrue("banks no profile plays: $stray", stray.isEmpty())
    }

    @Test
    fun `a bank round-trips through the reader`() {
        // One band of one run of two grains, a startup and a pop, at the output rate so nothing is resampled.
        val g1 = floatArrayOf(0f, 0.5f, 0f, -0.5f)
        val g2 = floatArrayOf(0f, 0.25f, 0f, -0.25f)
        val start = floatArrayOf(0.1f, 0.2f, 0.3f)
        val pop = floatArrayOf(0.9f, -0.9f)
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("EUCG".toByteArray()).putShort(1).putShort(sr.toShort()).putFloat(4f).putShort(1).putShort(2)
        buf.putFloat(12.5f).putShort(1).putShort(2)
        for (g in listOf(g1, g2)) { buf.putShort(g.size.toShort()); for (v in g) buf.putShort((v * 32767).toInt().toShort()) }
        buf.put(0).putInt(start.size); for (v in start) buf.putShort((v * 32767).toInt().toShort())
        buf.put(2).putInt(pop.size); for (v in pop) buf.putShort((v * 32767).toInt().toShort())
        val bank = GrainBank.read(ByteArrayInputStream(buf.array(), 0, buf.position()), sr)
        assertEquals(4f, bank.patternPerRev, 0f)
        assertEquals(12.5f, bank.lowestHz, 0f)
        assertEquals(2, bank.bands[0].runs[0].size)
        assertEquals(0.5f, bank.bands[0].runs[0][0][1], 0.001f)
        assertEquals(3, bank.startup.size)
        assertEquals(1, bank.pops.size)
        assertEquals(0.9f, bank.pops[0][0], 0.001f)
    }

    @Test
    fun `the engine fires at the recording's rate at idle and twice that at twice the revs`() {
        val misses = ArrayList<String>()
        for (p in recorded.filter { !it.preview }) {
            val bank = TestGrainBanks.loader(sr)(p.grainAsset!!)
            assertNotNull(p.key, bank)
            val idleLag = periodLag(render(p, p.idleRpm.toFloat()), bank!!.lowestHz)
            val fastLag = periodLag(render(p, p.idleRpm * 2f), bank.lowestHz * 2f)
            val nativeLag = sr / bank.lowestHz
            // A band is a quarter octave wide, so a grain's own cycle can sit 9% off the band's
            // rate, and each hop lands up to 2.5% early or late on purpose: the cycle the
            // listener hears is the band's rate give or take 15%.
            if (idleLag !in nativeLag * 0.85..nativeLag * 1.15) misses += "${p.key}: idle period ${idleLag.toInt()} samples, recording ${nativeLag.toInt()}"
            if (fastLag !in nativeLag * 0.425..nativeLag * 0.575) misses += "${p.key}: period at twice the revs ${fastLag.toInt()} samples, expected ${(nativeLag / 2).toInt()}"
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    private fun render(profile: EngineProfile, rpm: Float): FloatArray {
        val synth = EngineSynth(sr, TestGrainBanks.loader(sr))
        val params = EngineParams(rpm = rpm, load = 0.3f, profile = profile, masterGain = 1f, idleAmount = 1f)
        // Past the recorded start, so only the running grains are heard.
        val warm = FloatArray(4096)
        repeat(60) { synth.render(params, warm, 0, warm.size) }
        val out = FloatArray(1 shl 17)
        synth.render(params, out, 0, out.size)
        return out
    }

    /**
     * The lag of the strongest autocorrelation peak within 15% of the expected rate.
     * An even-firing engine repeats more strongly at its firing rate than at the
     * crank's, so the search stays close to the rate asked for.
     */
    private fun periodLag(x: FloatArray, expectHz: Float): Double {
        val lo = (sr / (expectHz * 1.15)).toInt()
        val hi = (sr / (expectHz / 1.15)).toInt().coerceAtMost(x.size / 2)
        var bestLag = lo; var best = Double.NEGATIVE_INFINITY
        val n = x.size - hi
        for (lag in lo..hi) {
            var s = 0.0
            var i = 0
            while (i < n) { s += x[i] * x[i + lag]; i += 3 }
            if (s > best) { best = s; bestLag = lag }
        }
        return bestLag.toDouble()
    }
}
