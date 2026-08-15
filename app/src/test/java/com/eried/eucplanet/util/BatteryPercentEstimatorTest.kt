package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.BatteryPercentSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbered cases come from the rider who asked for this (issue #15), so
 * they are the acceptance criteria rather than a reading of our own code: an S22
 * is 30 cells in series, and the voltages below are the ones they quoted.
 */
class BatteryPercentEstimatorTest {

    private val off = BatteryPercentSettings()
    private val enhanced = BatteryPercentSettings(useWheelLogEnhanced = true)
    private fun customMin(mv: Int) = BatteryPercentSettings(
        useCustomMinimumVoltage = true, minimumCellVoltageMv = mv,
    )

    private fun est(v: Float, cells: Int, s: BatteryPercentSettings, reported: Int = 77) =
        BatteryPercentEstimator.estimate(v, cells, s, reported)

    @Test
    fun `both options off keeps the wheel's own number`() {
        assertEquals(77, est(102.0f, 30, off))
    }

    @Test
    fun `enhanced curve on a 30S S22`() {
        assertEquals(0, est(96.0f, 30, enhanced))
        assertEquals(9, est(102.0f, 30, enhanced))
        assertEquals(100, est(125.25f, 30, enhanced))
    }

    @Test
    fun `enhanced curve on a 20S wheel`() {
        assertEquals(9, est(68.0f, 20, enhanced))
    }

    @Test
    fun `custom minimum of 3_30 V on a 30S S22`() {
        val s = customMin(3300)
        assertEquals(0, est(99.0f, 30, s))
        assertEquals(50, est(112.5f, 30, s))
        assertEquals(100, est(126.0f, 30, s))
    }

    @Test
    fun `custom minimum wins when both are on`() {
        // The rider naming a floor is more specific than picking a preset.
        val both = BatteryPercentSettings(
            useWheelLogEnhanced = true,
            useCustomMinimumVoltage = true,
            minimumCellVoltageMv = 3300,
        )
        assertEquals(50, est(112.5f, 30, both))
    }

    @Test
    fun `no voltage yet keeps the wheel's own number`() {
        // A wheel that has not reported voltage reads 0, which the curve would
        // otherwise turn into a confident 0%.
        assertEquals(77, est(0f, 30, enhanced))
        assertEquals(77, est(-1f, 30, enhanced))
    }

    @Test
    fun `an impossible cell count keeps the wheel's own number`() {
        assertEquals(77, est(102.0f, 0, enhanced))
        assertEquals(77, est(102.0f, 61, enhanced))
    }

    @Test
    fun `both curves stay inside nought to a hundred`() {
        for (cells in intArrayOf(16, 20, 24, 30)) {
            for (mv in 2000..4400 step 25) {
                val v = cells * mv / 1000f
                val e = est(v, cells, enhanced, reported = 50)
                val c = est(v, cells, customMin(3300), reported = 50)
                assert(e in 0..100) { "enhanced $v V / ${cells}S gave $e" }
                assert(c in 0..100) { "custom $v V / ${cells}S gave $c" }
            }
        }
    }

    @Test
    fun `an out-of-range floor cannot invert the scale`() {
        // sanitized() clamps this, but a hand-edited file reaching the curve
        // must not produce a percentage that climbs as the pack empties.
        val silly = customMin(4500)
        assertEquals(0, est(30 * 3.0f, 30, silly))
        assertEquals(100, est(30 * 4.3f, 30, silly))
    }
}
