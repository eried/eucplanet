package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The rider compared the throttle-chop pop as it was and laid twice, and picked the
 * louder one. That was on the recorded engines whose pop sat a decibel or two over
 * the engine: there it is raised, by [EngineProfile.popLevel]. Where the pop already
 * stood well clear (the diesel, the synthesised engines) it stays as it was.
 */
class EnginePopLevelTest {
    private val sr = 44100

    @Test
    fun `the pop is laid at the level its engine asks for`() {
        val raised = EngineProfile.PROFILES.filter { it.popLevel != 1f }
        assertTrue("no engine has a raised pop", raised.isNotEmpty())
        val misses = mutableListOf<String>()
        for (p in raised) {
            val silent = render(p.copy(popLevel = 0f))
            val plain = popAlone(render(p.copy(popLevel = 1f)), silent)
            val shipped = popAlone(render(p), silent)
            val gainDb = 20 * log10(shipped / plain)
            val wantDb = 20 * log10(p.popLevel.toDouble())
            if (!(abs(gainDb - wantDb) <= 1.0)) misses += "${p.key}: pop is %.1f dB up, popLevel says %.1f".format(gainDb, wantDb)
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `the engines the rider picked the louder pop on`() {
        assertEquals(2f, EngineProfile.byKey("V8_MUSCLE").popLevel, 0f)
        assertEquals(1.41f, EngineProfile.byKey("V_TWIN").popLevel, 0f)
        // Already well clear of their engines, left alone.
        for (key in listOf("SAMPLED_DIESEL_IVECO", "SAMPLED_BROKEN_EXHAUST", "FOUR_STROKE_SINGLE", "TWO_STROKE", "TRACTOR")) {
            assertEquals(key, 1f, EngineProfile.byKey(key).popLevel, 0f)
        }
    }

    @Test
    fun `engines cut from the same recording pop alike`() {
        val byBank = EngineProfile.PROFILES.filter { it.grainAsset != null }.groupBy { it.grainAsset }
        for ((bank, profiles) in byBank) {
            val levels = profiles.map { it.popLevel }.toSet()
            assertEquals("$bank: ${profiles.map { it.key to it.popLevel }}", 1, levels.size)
        }
    }

    @Test
    fun `a raised pop stays under the clip`() {
        for (p in EngineProfile.PROFILES.filter { it.popLevel != 1f }) {
            val peak = render(p).maxOf { abs(it) }
            assertTrue("${p.key}: peak $peak", peak < 0.9f)
        }
    }

    /**
     * A chop at mid revs: the engine with one pop in its first buffer. The noise
     * source is seeded, so two renders differ only by the pop's level.
     */
    private fun render(p: EngineProfile): FloatArray {
        val synth = EngineSynth(sr, TestGrainBanks.loader(sr))
        val rpm = p.idleRpm + (p.maxRpm - p.idleRpm) * 0.5f
        val chop = EngineParams(rpm = rpm, load = 0.04f, idleAmount = 1f, masterGain = 1f, profile = p)
        val warm = FloatArray(4096)
        repeat(40) { synth.render(chop, warm, 0, warm.size) }
        val out = FloatArray((sr * 0.15f).toInt())
        val first = 1024
        synth.render(chop.copy(pendingPops = 1), out, 0, first)
        synth.render(chop, out, first, out.size - first)
        return out
    }

    private fun popAlone(with: FloatArray, silent: FloatArray): Double =
        sqrt(with.indices.sumOf { val d = (with[it] - silent[it]).toDouble(); d * d } / with.size)
}
