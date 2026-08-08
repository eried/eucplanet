package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard (CONVENTIONS rule 13) for the dashboard stat buffers.
 *
 * Every catalog metric that advertises supportsStats = true must resolve to a
 * rolling-history buffer, otherwise its min / max / avg / last pills and its
 * sparkline have nothing to compute from and render an empty "--". That was
 * the exact bug this test defends against: POWER / MAX GPS SPEED and friends
 * showed blank pills because their buffer path was missing.
 *
 * The buffer path is either one of the six legacy typed lists on
 * [FullMetricHistory], or an allocated extras buffer in
 * [STAT_BUFFER_EXTRA_KEYS]. Since that set is derived from the catalog, a new
 * supportsStats metric is covered automatically, and any future change that
 * turns it into a hand-maintained list is caught here.
 */
class MetricStatBufferCoverageTest {

    private val statKeys: List<String> =
        MetricCatalog.all.filter { it.supportsStats }.map { it.key }

    @Test
    fun everyStatMetricHasABufferPath() {
        val covered = (LEGACY_STAT_METRIC_KEYS + STAT_BUFFER_EXTRA_KEYS).toSet()
        val missing = statKeys.filterNot { it in covered }
        assertTrue(
            "supportsStats metrics with no history buffer path: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun legacyAndExtrasBuffersAreDisjoint() {
        val overlap = STAT_BUFFER_EXTRA_KEYS.filter { it in LEGACY_STAT_METRIC_KEYS }
        assertTrue("legacy keys must not also be extras buffers: $overlap", overlap.isEmpty())
    }

    @Test
    fun extractorKeysAllHaveAllocatedBuffers() {
        val wheelKeys = EXTRA_HISTORY_METRICS.map { it.first }
        val unallocated = (wheelKeys + SOURCE_HISTORY_METRIC_KEYS)
            .filterNot { it in STAT_BUFFER_EXTRA_KEYS }
        assertTrue("extractor keys with no allocated buffer: $unallocated", unallocated.isEmpty())
    }

    @Test
    fun extrasBufferKeysAreUnique() {
        assertEquals(STAT_BUFFER_EXTRA_KEYS.size, STAT_BUFFER_EXTRA_KEYS.toSet().size)
    }
}
