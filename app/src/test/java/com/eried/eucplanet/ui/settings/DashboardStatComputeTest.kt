package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.repository.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards [computeDashboardStatValue], especially SUSTAINED_PEAK, which used to
 * be a plain max() (so a single-frame spike counted as the peak). It must now
 * report the highest level HELD for >= 2s and ignore shorter spikes.
 */
class DashboardStatComputeTest {

    private fun samples(vararg pairs: Pair<Long, Float>): List<MetricSample> =
        pairs.map { MetricSample(it.first, it.second) }

    @Test fun `sustained peak ignores a sub-2s spike`() {
        // 1 Hz stream sitting at 30, with a single 1s spike to 90.
        val s = samples(
            0L to 30f, 1000L to 30f, 2000L to 90f, 3000L to 30f,
            4000L to 30f, 5000L to 30f
        )
        val peak = computeDashboardStatValue(DashboardStat.SUSTAINED_PEAK, s, 0f)!!
        // The 90 spike lasted <2s, so the sustained peak is the held 30, not 90.
        assertEquals(30f, peak, 0.001f)
        // Plain MAX still sees the spike.
        assertEquals(90f, computeDashboardStatValue(DashboardStat.MAX, s, 0f)!!, 0.001f)
    }

    @Test fun `sustained peak reports a level held at least 2s`() {
        // Climbs to 60 and holds it for 3s, then drops.
        val s = samples(
            0L to 20f, 1000L to 40f, 2000L to 60f, 3000L to 60f,
            4000L to 60f, 5000L to 25f
        )
        val peak = computeDashboardStatValue(DashboardStat.SUSTAINED_PEAK, s, 0f)!!
        assertEquals(60f, peak, 0.001f)
    }

    @Test fun `min max avg are straightforward`() {
        val s = samples(0L to 10f, 1000L to 20f, 2000L to 30f)
        assertEquals(10f, computeDashboardStatValue(DashboardStat.MIN, s, 0f)!!, 0.001f)
        assertEquals(30f, computeDashboardStatValue(DashboardStat.MAX, s, 0f)!!, 0.001f)
        assertEquals(20f, computeDashboardStatValue(DashboardStat.AVG, s, 0f)!!, 0.001f)
    }

    @Test fun `current falls back when buffer empty, stats return null`() {
        assertEquals(42f, computeDashboardStatValue(DashboardStat.CURRENT, emptyList(), 42f)!!, 0.001f)
        assertNull(computeDashboardStatValue(DashboardStat.MAX, emptyList(), 42f))
    }
}
