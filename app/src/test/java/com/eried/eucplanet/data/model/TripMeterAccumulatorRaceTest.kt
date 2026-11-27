package com.eried.eucplanet.data.model

import com.eried.eucplanet.raceThreads
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The thread race from next-experimental's TripMeterAccumulatorTest. The rest
 * of that test lives in shared/commonTest and runs on both phones; this case
 * needs JVM threads, so it runs here against the shared accumulator.
 */
class TripMeterAccumulatorRaceTest {

    @Test
    fun `a reset from another thread does not break a tick in progress`() {
        // The meter ticks once a second on its own thread; its Reset button
        // and Reset metrics arrive from the screen's. A split on every tick
        // here, so the log is always being written while it is cleared. The
        // ticking side also clears now and then, to keep the log short once
        // the other side has finished.
        val acc = TripMeterAccumulator(intervalKm = 0.001f)
        val thrown = raceThreads(
            rounds = 2_000_000,
            sides = arrayOf(
                { i ->
                    acc.onTick(0.001f, 1000L, 20f, 80, i.toLong())
                    acc.snapshot()
                    if (i % 64 == 63) acc.reset()
                },
                { acc.reset() },
            ),
        )
        assertNull("$thrown", thrown)
    }
}
