package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift guard (CONVENTIONS rule 13) for metrics that have no live source.
 *
 * [MetricStatBufferCoverageTest] checks that every extractor has a buffer. This
 * checks the other direction, which is the one that bites: a metric can sit in
 * the catalog with a buffer, a sparkline and a slot in the picker while nothing
 * ever writes a value to it. The rider picks it and gets a tile that reads "--"
 * forever, and nothing anywhere says so.
 *
 * RANGE_ESTIMATE shipped in exactly that state. So the sourceless set is
 * declared here instead of being whatever falls out: adding a metric with no
 * source makes this fail until you name it, and giving one a source makes it
 * fail until you take it off the list.
 */
class MetricSourceCoverageTest {

    /**
     * Catalog metrics that knowingly have no live source yet. Every entry is a
     * tile a rider can choose and will see nothing in, so this list should only
     * ever get shorter.
     */
    private val knownSourceless = setOf(
        // Needs integrated altitude history, which nothing keeps yet.
        "SLOPE",
        // Needs adapter-side plumbing: no family puts RPM on WheelData.
        "MOTOR_RPM",
        // Safety headroom against the PWM ceiling. Never computed anywhere.
        "HEADROOM",
    )

    @Test
    fun `every stat metric has a source, or is declared sourceless`() {
        val sourced = (
            LEGACY_STAT_METRIC_KEYS +
                SOURCE_HISTORY_METRIC_KEYS +
                EXTRA_HISTORY_METRICS.map { it.first }
            ).toSet()
        val sourceless = MetricCatalog.all
            .filter { it.supportsStats }
            .map { it.key }
            .filterNot { it in sourced }
            .toSet()
        assertEquals(
            "Metrics with no live source changed. A new one renders an empty " +
                "tile the rider can still pick, so either wire it up or add it " +
                "to knownSourceless deliberately.",
            knownSourceless,
            sourceless,
        )
    }
}
