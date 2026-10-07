package com.eried.eucplanet.research

import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.service.AlarmLogic
import com.eried.eucplanet.util.LiveBatteryEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random

/**
 * A study, not only a test: one noisy ride from 70 % to 10 %, run through the
 * real envelope and the real alarm decision, with every sample and every fire
 * written to `build/study/` so the result can be graphed and argued with.
 *
 * The ride is the one the rider described. The resting level falls a point a
 * minute. Every so often the reading spikes DOWN ten points for four seconds,
 * overshoots UP a point for one second on the way back, then sits steady on
 * the resting level again with a little jitter, which is what an 84 V pack
 * does when the rider opens the throttle and lets go. One two-minute stretch
 * of unbroken load is in there too, at minute 35, because a climb with no
 * let-up is the case the envelope handles least well and a study that left
 * it out would be marketing.
 *
 * Four rules watch it, two on the raw percentage and two on the envelope, at
 * 50 % and 20 %, in "Many" mode with the default five-second cooldown: the
 * configuration the emulator run used. The assertions below are the claims
 * the graphs make, so the graphs cannot quietly drift from the code.
 */
class BatteryEnvelopeStudyTest {

    private data class Rule(val name: String, val onEnvelope: Boolean, val threshold: Float) {
        var wasActive = false
        var lastFireMs = Long.MIN_VALUE / 4
        val fires = ArrayList<Pair<Long, Float>>()
    }

    private data class Spike(val startMs: Long)

    @Test fun `study - a noisy pack from 70 to 10, plain alarms against envelope alarms`() {
        val random = Random(20260907L)
        val dtMs = 250L
        val totalMs = 60 * 60_000L
        val cooldownMs = 5_000L

        // Spikes: a gap of 15 to 45 s between them, the whole ride long.
        val spikes = ArrayList<Spike>()
        var t = 8_000L
        while (t < totalMs) {
            spikes += Spike(t)
            t += 15_000L + (random.nextDouble() * 30_000L).toLong()
        }
        val climbStartMs = 35 * 60_000L
        val climbEndMs = climbStartMs + 120_000L

        val env = LiveBatteryEnvelope()
        val rules = listOf(
            Rule("raw<50", onEnvelope = false, threshold = 50f),
            Rule("raw<20", onEnvelope = false, threshold = 20f),
            Rule("est<50", onEnvelope = true, threshold = 50f),
            Rule("est<20", onEnvelope = true, threshold = 20f),
        )

        val out = File("build/study").apply { mkdirs() }
        val samples = File(out, "samples.csv").bufferedWriter()
        samples.write("t_s,resting,raw,est\n")

        var lastEst = Float.NaN
        var rises = 0
        var now = 0L
        while (now <= totalMs) {
            val minutes = now / 60_000f
            val resting = 70f - minutes

            // The spike shape: 4 s ten points down (half a second of ramp in),
            // then 1 s one point up, then back on the resting level.
            var offset = 0f
            for (s in spikes) {
                val dt = now - s.startMs
                if (dt < 0 || dt > 5_000L) continue
                offset = when {
                    dt < 500L -> -10f * (dt / 500f)
                    dt < 4_000L -> -10f
                    else -> +1f
                }
            }
            if (now in climbStartMs until climbEndMs) offset = -8f
            val jitter = (random.nextGaussian() * 0.4).toFloat()
            // Wheels report whole percent.
            val raw = Math.round((resting + offset + jitter).coerceIn(1f, 100f)).toFloat()

            val est = env.sample(now, raw)
            if (!est.isNaN() && !lastEst.isNaN() && est > lastEst + 0.01f) rises++
            lastEst = est

            for (r in rules) {
                val value = if (r.onEnvelope) est else raw
                if (value.isNaN()) continue
                val matched = AlarmLogic.matchesNow(value, AlarmComparator.LESS_THAN.name, r.threshold)
                val fire = AlarmLogic.shouldFire(
                    matched = matched,
                    wasActive = r.wasActive,
                    msSinceLastFire = now - r.lastFireMs,
                    cooldownMs = cooldownMs,
                    repeatWhileActive = true,
                )
                if (fire) { r.fires += now to value; r.lastFireMs = now }
                r.wasActive = matched
            }

            samples.write(String.format(Locale.US, "%.2f,%.2f,%.0f,%s\n",
                now / 1000f, resting, raw, if (est.isNaN()) "" else String.format(Locale.US, "%.1f", est)))
            now += dtMs
        }
        samples.close()

        File(out, "fires.csv").bufferedWriter().use { w ->
            w.write("t_s,rule,value\n")
            for (r in rules) for ((at, v) in r.fires)
                w.write(String.format(Locale.US, "%.2f,%s,%.1f\n", at / 1000f, r.name, v))
        }
        File(out, "spikes.csv").bufferedWriter().use { w ->
            w.write("t_s\n")
            for (s in spikes) w.write(String.format(Locale.US, "%.2f\n", s.startMs / 1000f))
            w.write(String.format(Locale.US, "climb,%.2f,%.2f\n", climbStartMs / 1000f, climbEndMs / 1000f))
        }

        // --- the claims the graphs make -------------------------------------
        fun crossingS(threshold: Float) = ((70f - threshold) * 60f)   // resting = threshold
        val byName = rules.associateBy { it.name }
        fun first(name: String) = byName.getValue(name).fires.first().first / 1000f
        fun earlyFires(name: String, threshold: Float) =
            byName.getValue(name).fires.count { it.first / 1000f < crossingS(threshold) }

        File(out, "summary.txt").writeText(buildString {
            for (r in rules) {
                val thr = r.threshold
                appendLine(String.format(Locale.US,
                    "%-7s first %6.1fs  count %4d  before-crossing %4d  (crossing at %.0fs)",
                    r.name, r.fires.first().first / 1000f, r.fires.size, earlyFires(r.name, thr), crossingS(thr)))
            }
            appendLine("envelope rises: $rises")
        })
        println(File(out, "summary.txt").readText())

        // The envelope never rose, over a whole hour of spikes and overshoots.
        assertEquals("the envelope rose", 0, rises)
        // The plain alarms fired on sag long before the pack was there.
        assertTrue("raw<50 should fire early on spikes", earlyFires("raw<50", 50f) > 0)
        assertTrue("raw<20 should fire early on spikes", earlyFires("raw<20", 20f) > 0)
        // The envelope alarms did not, and were not late by more than the
        // two-bucket lag plus the overshoot the p90 can pick up.
        assertEquals("est<50 fired before the pack reached 50", 0, earlyFires("est<50", 50f))
        assertEquals("est<20 fired before the pack reached 20", 0, earlyFires("est<20", 20f))
        assertTrue("est<50 lag: ${first("est<50") - crossingS(50f)}s", first("est<50") - crossingS(50f) <= 150f)
        assertTrue("est<20 lag: ${first("est<20") - crossingS(20f)}s", first("est<20") - crossingS(20f) <= 150f)
    }
}
