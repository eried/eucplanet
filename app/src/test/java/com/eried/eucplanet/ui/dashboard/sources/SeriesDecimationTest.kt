package com.eried.eucplanet.ui.dashboard.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the Compare-tab series decimation that fixed the ride-time ANR.
 * The snapshot combine ticks at the ~50 Hz phone-IMU rate; without decimation
 * the per-source buffers grew to ~15k points and both the append and the chart
 * redraw ran O(n) at 50 Hz on the main thread until Android raised the
 * app-not-responding dialog. [DataSourcesViewModel.appendDecimated] bounds the
 * rate (and therefore the buffer size) and, crucially, returns the SAME list
 * instance on the skip path so no StateFlow emission (and no redraw) happens.
 */
class SeriesDecimationTest {

    private val windowMs = 300_000L
    private val intervalMs = 100L

    @Test fun `50Hz feed is decimated to the min interval`() {
        var pts = emptyList<Pair<Long, Float>>()
        // 1000 samples 20 ms apart = a 20 s span at 50 Hz.
        for (i in 0 until 1000) {
            pts = DataSourcesViewModel.appendDecimated(pts, i * 20L, i.toFloat(), windowMs, intervalMs)
        }
        // 20 s at one-per-100 ms is ~201 kept, nowhere near the raw 1000.
        assertTrue("expected ~200 kept, got ${pts.size}", pts.size in 195..205)
    }

    @Test fun `skip path returns the exact same list instance`() {
        val first = DataSourcesViewModel.appendDecimated(emptyList(), 0L, 1f, windowMs, intervalMs)
        // 50 ms later is inside the 100 ms interval, so nothing changes.
        val skipped = DataSourcesViewModel.appendDecimated(first, 50L, 2f, windowMs, intervalMs)
        assertSame("too-soon append must return the same instance (no StateFlow emit)", first, skipped)
        assertEquals(1, skipped.size)
    }

    @Test fun `points older than the window are dropped`() {
        var pts = emptyList<Pair<Long, Float>>()
        // 1 Hz feed (well above the interval, so every sample is kept) for 100 s
        // against a 10 s window: only the last ~10-11 survive.
        for (i in 0 until 100) {
            pts = DataSourcesViewModel.appendDecimated(pts, i * 1000L, i.toFloat(), 10_000L, intervalMs)
        }
        assertTrue("window should bound the buffer, got ${pts.size}", pts.size <= 11)
        assertEquals("newest sample must be present", 99f, pts.last().second, 0f)
    }

    @Test fun `a sample after a gap is kept`() {
        var pts = DataSourcesViewModel.appendDecimated(emptyList(), 0L, 1f, windowMs, intervalMs)
        pts = DataSourcesViewModel.appendDecimated(pts, 50L, 2f, windowMs, intervalMs)   // too soon
        assertEquals(1, pts.size)
        pts = DataSourcesViewModel.appendDecimated(pts, 5_000L, 3f, windowMs, intervalMs) // after a gap
        assertEquals(2, pts.size)
        assertEquals(3f, pts.last().second, 0f)
    }
}
