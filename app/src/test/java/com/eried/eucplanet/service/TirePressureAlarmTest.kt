package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Tire pressure as an alarm metric, covering the two things that would break
 * quietly rather than loudly.
 *
 * A wheel with no TPMS reports 0 kPa, which is under every low-pressure
 * threshold a rider could set, so the rule has to be skipped rather than
 * evaluated. And bar has to survive an integer stepper: whole bar would give
 * a rider four settings across the whole useful range, so the editor works in
 * tenths and the conversion has to round-trip through that.
 */
class TirePressureAlarmTest {

    @Test fun `it watches from below, like battery and voltage`() {
        // A tyre alarm is about losing air. Born as ">= " it would be useless
        // and the rider would have to know to flip it.
        assertEquals(
            AlarmComparator.LESS_THAN,
            AlarmMetric.TIRE_PRESSURE.defaultComparator,
        )
    }

    @Test fun `the range covers a real tyre and stops well past one`() {
        val max = AlarmLogic.metricReadMax(AlarmMetric.TIRE_PRESSURE.name)
        val min = AlarmLogic.metricReadMin(AlarmMetric.TIRE_PRESSURE.name)
        assertEquals(0f, min, 0.001f)
        // 500 kPa is about 72 psi: above anything an EUC tyre is run at, so a
        // rider is never clamped below the value they want.
        assertTrue("a 45 psi threshold must fit", Units.pressureToKpa(45f, "psi") < max)
        assertTrue("a 4 bar threshold must fit", Units.pressureToKpa(4f, "bar") < max)
    }

    @Test fun `psi round-trips through the editor as whole numbers`() {
        for (psi in listOf(20, 28, 35, 42, 50)) {
            val kpa = Units.pressureToKpa(psi.toFloat(), "psi")
            val back = Units.pressure(kpa, "psi").roundToInt()
            assertEquals("$psi psi", psi, back)
        }
    }

    @Test fun `bar round-trips at one decimal, which whole bar could not`() {
        // The editor holds tenths: 24 means 2.4 bar. Without that a rider
        // choosing a tyre pressure would be picking between 2 and 3.
        for (tenths in listOf(15, 20, 24, 28, 33, 40)) {
            val bar = tenths / 10f
            val kpa = Units.pressureToKpa(bar, "bar")
            val backTenths = (Units.pressure(kpa, "bar") * 10f).roundToInt()
            assertEquals("$bar bar", tenths, backTenths)
        }
    }

    @Test fun `the two units disagree about the same tyre by the right amount`() {
        // 2.4 bar is 240 kPa is about 34.8 psi. If this drifts, one of the
        // conversions has picked up a wrong constant.
        val kpa = Units.pressureToKpa(2.4f, "bar")
        assertEquals(240f, kpa, 0.01f)
        assertEquals(34.8f, Units.pressure(kpa, "psi"), 0.1f)
    }

    @Test fun `a wheel with no sensor is skipped, not read as a flat tyre`() {
        // No sensor: the rule is skipped. Evaluated instead, a low-pressure
        // alarm on a wheel without TPMS would fire on the first frame and
        // never stop.
        assertEquals(null, AlarmLogic.tirePressureForAlarm(WheelData()))
    }

    @Test fun `a cap reporting zero IS a flat tyre, and must reach the rule`() {
        // The whole reason a rider screws a sensor onto a valve. A cap on a
        // flat tyre reports exactly 0 kPa, and the guard this replaced - skip
        // anything not above zero - threw that reading away, so the one alarm
        // that matters could never fire.
        val flat = WheelData(tirePressureKpa = 0f, hasTirePressure = true)
        assertEquals(0f, AlarmLogic.tirePressureForAlarm(flat))
        assertTrue(
            "a 2 bar rule has to match a flat tyre",
            AlarmLogic.matchesNow(
                AlarmLogic.tirePressureForAlarm(flat)!!,
                AlarmComparator.LESS_THAN.name,
                Units.pressureToKpa(2f, "bar"),
            ),
        )
    }

    @Test fun `a healthy reading passes through untouched`() {
        val ok = WheelData(tirePressureKpa = 240f, hasTirePressure = true)
        assertEquals(240f, AlarmLogic.tirePressureForAlarm(ok))
    }
}
