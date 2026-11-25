package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.repository.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DashboardStatCacheTest {

    private val stats = listOf(
        DashboardStat.MIN, DashboardStat.MAX, DashboardStat.AVG, DashboardStat.SUSTAINED_PEAK,
        DashboardStat.MEDIAN, DashboardStat.P75, DashboardStat.P95, DashboardStat.P99,
    )

    /** The stats as they were worked out before, on a boxed copy of the values. */
    private fun reference(stat: DashboardStat, samples: List<MetricSample>): Float? {
        val values = samples.map { it.value }
        fun pct(p: Double): Float {
            val sorted = values.sorted()
            val rank = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val lo = kotlin.math.floor(rank).toInt()
            val hi = kotlin.math.ceil(rank).toInt()
            if (lo == hi) return sorted[lo]
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo).toFloat()
        }
        return when (stat) {
            DashboardStat.MIN -> values.min()
            DashboardStat.MAX -> values.max()
            DashboardStat.AVG -> values.average().toFloat()
            DashboardStat.MEDIAN -> pct(0.50)
            DashboardStat.P75 -> pct(0.75)
            DashboardStat.P95 -> pct(0.95)
            DashboardStat.P99 -> pct(0.99)
            else -> computeDashboardStatValue(stat, samples, 0f)
        }
    }

    @Test
    fun `every stat gives exactly what it gave before, on many random histories`() {
        val rnd = Random(11)
        repeat(300) {
            val n = rnd.nextInt(1, 400)
            val samples = List(n) { i ->
                val v = when (rnd.nextInt(40)) {
                    0 -> 0f
                    1 -> -0f
                    2 -> Float.NaN
                    else -> rnd.nextFloat() * 200f - 50f
                }
                MetricSample(1_000L + i * 1000L, v)
            }
            for (stat in stats) {
                val want = reference(stat, samples)
                val got = computeDashboardStatValue(stat, samples, 0f)
                assertEquals("$stat on $n samples", want?.toRawBits(), got?.toRawBits())
            }
        }
    }

    @Test
    fun `a chip is worked out once per history list, however often it is asked`() {
        val cache = DashboardStatCache()
        val history = List(300) { MetricSample(it * 1000L, it.toFloat()) }
        val first = cache.value("SPEED", DashboardStat.P95, history)
        repeat(50) { assertEquals(first, cache.value("SPEED", DashboardStat.P95, history)) }
        assertEquals(computeDashboardStatValue(DashboardStat.P95, history, 0f), first)
    }

    @Test
    fun `a new sample is a new list and gives a new value`() {
        val cache = DashboardStatCache()
        val a = List(10) { MetricSample(it * 1000L, it.toFloat()) }
        val b = a + MetricSample(10_000L, 500f)
        assertEquals(9f, cache.value("SPEED", DashboardStat.MAX, a))
        assertEquals(500f, cache.value("SPEED", DashboardStat.MAX, b))
    }

    @Test
    fun `chips do not share a value, by slot or by stat`() {
        val cache = DashboardStatCache()
        val h = List(10) { MetricSample(it * 1000L, it.toFloat()) }
        assertEquals(0f, cache.value("SPEED", DashboardStat.MIN, h))
        assertEquals(9f, cache.value("SPEED", DashboardStat.MAX, h))
        assertEquals(9f, cache.value("cell/SPEED", DashboardStat.MAX, h))
        assertNull(cache.value("SPEED", DashboardStat.NONE, h))
    }

    @Test
    fun `speed of the stats, old way against new`() {
        // Not a pass or fail on time. Prints what one dashboard pass costs for
        // eight chips on a 15 minute history at four samples a second.
        val rnd = Random(3)
        val history = List(3600) { MetricSample(it * 250L, rnd.nextFloat() * 60f) }
        fun time(block: () -> Unit): Double {
            repeat(30) { block() }
            val t0 = System.nanoTime()
            repeat(200) { block() }
            return (System.nanoTime() - t0) / 200 / 1000.0
        }
        val old = time { for (s in stats) reference(s, history) }
        val new = time { for (s in stats) computeDashboardStatValue(s, history, 0f) }
        val cache = DashboardStatCache()
        val cached = time { for (s in stats) cache.value("SPEED", s, history) }
        println("STATBENCH old=%.0f us new=%.0f us cached=%.2f us per pass of 8 chips, 3600 samples".format(old, new, cached))
        assertTrue(cached < new)
    }
}
