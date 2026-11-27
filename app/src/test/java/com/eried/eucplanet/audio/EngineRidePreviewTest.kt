package com.eried.eucplanet.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings preview of the decel character revs the engine up and lets off, to
 * let the rider hear the pop they are choosing. Whether the let-off counted as a
 * chop hung on a few milliseconds of how long the preview thread had slept, so
 * most presses played no pop at all.
 */
class EngineRidePreviewTest {

    @Test
    fun `the throttle-chop preview pops, on every engine that can`() {
        val silent = mutableListOf<String>()
        for (p in EngineProfile.PROFILES.filter { it.supportsPops && it.decelPopProbability > 0f }) {
            // Sleeps run a little long on a phone, never short: the preview has to work on time and late.
            for (lateMs in listOf(0L, 3L, 15L)) {
                val pops = popsIn(p, lateMs)
                if (pops != 1) silent += "${p.key} with steps $lateMs ms late: $pops pops"
            }
        }
        assertTrue(silent.joinToString("\n"), silent.isEmpty())
    }

    @Test
    fun `a smooth decel character stays silent in the preview`() {
        val p = EngineProfile.byKey("V8_MUSCLE")
        assertTrue(popsIn(p, 0L, decelChar = "SMOOTH") == 0)
    }

    private fun popsIn(p: EngineProfile, lateMs: Long, decelChar: String = "STANDARD"): Int {
        val model = EngineDynamics().apply {
            profile = p
            pwmEverNonZero = true
            this.decelChar = decelChar
            random = { 0.0 }          // the dice always say pop: this is about the chop being seen
        }
        var now = 1_000L
        var pops = 0
        for (step in EngineRide.preview("DECEL", 2500L, 50f)) {
            val params = if (step.direct) model.direct(now, step.rpmFraction, step.load)
            else model.tick(now, step.speedKmh, step.pwmPercent)
            pops += params.pendingPops
            now += step.holdMs + if (step.holdMs > 0) lateMs else 0L
        }
        return pops
    }
}
