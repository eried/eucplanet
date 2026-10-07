package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The battery envelope follows what the rider does, not the voltage sag:
 * steady down while riding, flat while stopped, up on a sustained regen
 * descent - anchored to the trip's real start and end battery.
 */
class BatteryEnvelopeTest {

    /** 5 s sampling: drive 0-300 s at 20 A, stop 300-420 s at 0 A, regen
     *  descent 420-600 s at -10 A. Battery sags under load and recovers at
     *  the stop, the way a real pack reads. */
    private fun ride(): Triple<FloatArray, FloatArray, FloatArray> {
        val t = ArrayList<Float>()
        val batt = ArrayList<Float>()
        val cur = ArrayList<Float>()
        var i = 0
        while (i * 5 <= 600) {
            val sec = i * 5f
            t.add(sec)
            val trueSoc = when {
                sec <= 300f -> 90f - sec / 300f * 6f          // 90 -> 84 driving
                sec <= 420f -> 84f                            // flat at the stop
                else -> 84f + (sec - 420f) / 180f * 1f        // 84 -> 85 regen
            }
            // Load sag: reads ~2 % low under drive, bounces back at the stop.
            val sag = if (sec in 5f..295f) 2f else 0f
            batt.add(trueSoc - sag + (if (i % 3 == 0) 0.5f else -0.5f))
            cur.add(
                when {
                    sec < 300f -> 20f
                    sec < 420f -> 0f
                    else -> -10f
                }
            )
            i++
        }
        return Triple(t.toFloatArray(), batt.toFloatArray(), cur.toFloatArray())
    }

    @Test fun `primary model - down riding, flat stopped, up on regen, anchored ends`() {
        val (t, batt, cur) = ride()
        val env = BatteryEnvelope.compute(t, batt, cur)
        assertEquals(t.size, env.size)
        // Anchors: the ends sit on the measured medians, not the sagged dip.
        assertTrue("start anchor ${env.first()}", env.first() in 87f..91f)
        assertTrue("end anchor ${env.last()}", env.last() in 83.5f..86f)
        // Driving: never rises, and actually descends across the phase.
        val drive = env.filterIndexed { i, _ -> t[i] <= 300f }
        for (j in 1 until drive.size) assertTrue(drive[j] <= drive[j - 1] + 0.001f)
        assertTrue(drive.last() < drive.first() - 2f)
        // Stopped: flat (current is zero, the integral does not move).
        val stop = env.filterIndexed { i, _ -> t[i] in 305f..415f }
        assertTrue(stop.max() - stop.min() < 0.2f)
        // Regen: the line steps UP.
        val regenStart = env[t.indexOfFirst { it >= 430f }]
        assertTrue("regen must climb", env.last() > regenStart + 0.2f)
    }

    @Test fun `values latch per 30 s bucket`() {
        val (t, batt, cur) = ride()
        val env = BatteryEnvelope.compute(t, batt, cur)
        // Every sample inside one bucket carries the bucket's opening value.
        for (i in t.indices) {
            val k = (t[i] / 30f).toInt()
            val opening = env[t.indexOfFirst { (it / 30f).toInt() == k }]
            assertEquals(opening, env[i], 0.0001f)
        }
    }

    @Test fun `near-zero net charge falls back to the battery walk`() {
        // Symmetric drive and regen: the integral cancels, the warp would
        // divide by nothing. The battery-only walk takes over.
        val n = 121
        val t = FloatArray(n) { it * 5f }
        val batt = FloatArray(n) { 90f - it * 0.05f }
        val cur = FloatArray(n) { if (it < n / 2) 10f else -10f }
        val env = BatteryEnvelope.compute(t, batt, cur)
        assertEquals(n, env.size)
        for (j in 1 until n) assertTrue(env[j] <= env[j - 1] + 0.001f)
    }

    @Test fun `battery-only walk holds through a single spike, rises on two`() {
        // 30 s buckets: 80, 80, 85 (single recovery spike), 80, then a real
        // sustained rise 85, 85.
        val perBucket = listOf(80f, 80f, 85f, 80f, 85f, 85f)
        val t = ArrayList<Float>()
        val batt = ArrayList<Float>()
        perBucket.forEachIndexed { k, v ->
            repeat(6) { s -> t.add(k * 30f + s * 5f); batt.add(v) }
        }
        val cur = FloatArray(t.size) { Float.NaN }  // no current column
        val env = BatteryEnvelope.compute(t.toFloatArray(), batt.toFloatArray(), cur)
        // Bucket 2's lone 85 does not drag the line up...
        assertEquals(80f, env[2 * 6 + 3], 0.001f)
        // ...but the sustained pair at the end does.
        assertEquals(85f, env.last(), 0.001f)
    }

    @Test fun `the chart ships off, opt-in like the other extras`() {
        val td = File("src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt").readText()
        val extras = td.substringAfter("EXTRA_CHART_KEYS = setOf(").substringBefore(")")
        assertTrue(extras.contains("\"batteryEnvelope\""))
        assertTrue(td.contains("\"batteryEnvelope\" in extraCharts"))
        // The stepped look is intentional: the values feed the card raw.
        assertTrue(td.contains("BatteryEnvelope.compute"))
    }

    @Test fun `every locale carries the label`() {
        val missing = File("src/main/res").listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .filter { !File(it, "strings.xml").readText().contains("recording_chart_battery_envelope") }
            .map { it.name }
        assertTrue("missing: $missing", missing.isEmpty())
    }
}
