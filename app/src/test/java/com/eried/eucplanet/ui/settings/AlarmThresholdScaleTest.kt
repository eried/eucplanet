package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.service.AlarmLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The threshold editor has to work in whatever unit the rider picked.
 *
 * A tyre tops out near 500 kPa, which is 72 psi, 5 bar, 5.1 kgf/cm2 or half a
 * megapascal. The stepper holds an Int, so a unit whose whole range rounds to
 * a single digit gives a rider a control that will not move: in MPa the range
 * was 0 to 0 and the pressure alarm could not be edited at all. Each unit
 * needs a scale wide enough to be steppable and a round trip that survives it.
 */
class AlarmThresholdScaleTest {

    private val units = listOf("psi", "bar", "kpa", "kgf", "mpa")
    private val metric = AlarmMetric.TIRE_PRESSURE
    private val maxKpa = AlarmLogic.metricReadMax(metric.name)

    private fun shownRange(unit: String): Int {
        val lo = displayThreshold(metric, 0f, "km", "C", unit).roundToInt()
        val hi = displayThreshold(metric, maxKpa, "km", "C", unit).roundToInt()
        return hi - lo
    }

    @Test fun `every pressure unit leaves the rider something to step through`() {
        for (unit in units) {
            assertTrue(
                "$unit collapses the stepper to ${shownRange(unit)} steps",
                shownRange(unit) >= 20,
            )
        }
    }

    @Test fun `a threshold survives the trip out to the rider and back`() {
        // 250 kPa is a normal EUC tyre, and the value a rider would actually
        // type. It has to come back as itself in every unit.
        for (unit in units) {
            val shown = displayThreshold(metric, 250f, "km", "C", unit)
            val back = internalThreshold(metric, shown.roundToInt().toFloat(), "km", "C", unit)
            assertTrue("$unit lost the value: 250 became $back", abs(back - 250f) < 6f)
        }
    }

    @Test fun `the rule row prints the pressure, not the editing scale`() {
        // 300 kPa is 3 bar. The row used to print the stepper's raw integer,
        // so it read "30bar", and a 1.5 g rule read "15g".
        assertEquals("3.0", formatThreshold(metric, 300f, "km", "C", "bar"))
        assertEquals("0.30", formatThreshold(metric, 300f, "km", "C", "mpa"))
        assertEquals("44", formatThreshold(metric, 300f, "km", "C", "psi"))
        assertEquals("1.5", formatThreshold(AlarmMetric.G_FORCE, 1.5f, "km", "C", "bar"))
    }

    @Test fun `every pressure unit converts both ways`() {
        // The bug this catches: kgf and MPa were taught to pressure() and to
        // the unit symbol, but not to pressureToKpa. A threshold typed in
        // those units was stored as though it were kPa, so a 2.5 kgf alarm
        // became 2.5 kPa and could never fire.
        for (unit in units) {
            val shown = com.eried.eucplanet.util.Units.pressure(250f, unit)
            val back = com.eried.eucplanet.util.Units.pressureToKpa(shown, unit)
            assertTrue("$unit is one-way: 250 kPa came back as $back", abs(back - 250f) < 0.5f)
        }
    }

    @Test fun `the decimals match the scale that produced them`() {
        for (unit in units) {
            val decimals = displayDecimals(metric, unit)
            val scale = displayScale(metric, unit)
            assertEquals("$unit: scale and decimals disagree", scale, Math.pow(10.0, decimals.toDouble()).toFloat())
        }
    }
}
