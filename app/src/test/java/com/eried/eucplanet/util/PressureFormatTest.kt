package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TpmsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One formatter for every pressure the app prints.
 *
 * Four screens used to write the number out themselves and only two of them
 * knew that kgf/cm2 and MPa exist; the other two printed the value in bar with
 * the unit the rider chose next to it, which is a wrong number rather than a
 * missing one. These tests are the reason a fifth screen cannot repeat it.
 */
class PressureFormatTest {

    /** 3.6 bar. The pressure the sensor's own decode was confirmed against. */
    private val kpa = 360f

    @Test fun `every unit prints its own value and its own name`() {
        assertEquals("3.60 bar", Units.formatPressure(kpa, "bar"))
        assertEquals("52.2 psi", Units.formatPressure(kpa, "psi"))
        assertEquals("360 kPa", Units.formatPressure(kpa, "kpa"))
        assertEquals("3.67 kgf/cm²", Units.formatPressure(kpa, "kgf"))
        assertEquals("0.360 MPa", Units.formatPressure(kpa, "mpa"))
    }

    @Test fun `MPa keeps enough decimals to see a pump working`() {
        // A whole bar is 0.1 MPa. At two decimals, letting air out of a tyre
        // by half a bar moves the last digit by 5 and a rider topping up by a
        // tenth sees nothing move at all.
        val soft = Units.formatPressure(340f, "mpa")
        val hard = Units.formatPressure(350f, "mpa")
        assertTrue("$soft and $hard must differ", soft != hard)
    }

    @Test fun `psi is floored, matching the number on the wheel`() {
        // The wheel sends whole kPa and truncates its own conversion. 210 kPa
        // is 30.458 psi; rounding shows 30.5 while the wheel shows 30.4, and a
        // rider comparing the two screens finds a bug that is not there.
        assertEquals("30.4 psi", Units.formatPressure(210f, "psi"))
    }

    @Test fun `a flat tyre prints as a pressure, not as nothing`() {
        // 0 kPa is what a cap on a flat tyre reports. It has to read as zero
        // pressure, in the rider's unit, like any other reading.
        assertEquals("0.00 bar", Units.formatPressure(0f, "bar"))
        assertEquals("0.0 psi", Units.formatPressure(0f, "psi"))
    }

    @Test fun `an unknown unit falls back to kPa rather than lying`() {
        // Nothing should reach here - sanitized() clamps the setting - but if
        // something does, printing the raw value under its own name is honest.
        assertEquals("360 kPa", Units.formatPressure(kpa, "nonsense"))
    }

    @Test fun `the rider's choice beats the distance unit, in both directions`() {
        // The reason this setting exists. EUC riders run psi in a tyre while
        // measuring the road in kilometres, and the old derivation could not
        // express that.
        val metricRiderOnPsi = AppSettings(
            unitDistance = "km",
            tpms = TpmsSettings(pressureUnit = "psi"),
        )
        assertEquals("psi", Units.effectivePressureUnit(metricRiderOnPsi))

        val imperialRiderOnBar = AppSettings(
            unitDistance = "mi",
            tpms = TpmsSettings(pressureUnit = "bar"),
        )
        assertEquals("bar", Units.effectivePressureUnit(imperialRiderOnBar))
    }

    @Test fun `unset still follows the unit system, as it always did`() {
        assertEquals("psi", Units.effectivePressureUnit(AppSettings(unitDistance = "mi")))
        assertEquals("bar", Units.effectivePressureUnit(AppSettings(unitDistance = "km")))
    }

    @Test fun `every unit the picker offers is one the formatter knows`() {
        // The drift guard. A unit added to the settings list and forgotten
        // here would print its own name beside a kPa value.
        for (unit in TpmsSettings.PRESSURE_UNIT_VALUES) {
            if (unit.isEmpty()) continue
            val shown = Units.formatPressure(kpa, unit)
            assertEquals("$unit must print its own name", Units.pressureUnit(unit),
                shown.substringAfter(' '))
            // And round-trip, so a threshold typed in that unit means what the
            // rider typed.
            val back = Units.pressureToKpa(Units.pressure(kpa, unit), unit)
            assertEquals("$unit must round-trip", kpa, back, 0.01f)
        }
    }
}
