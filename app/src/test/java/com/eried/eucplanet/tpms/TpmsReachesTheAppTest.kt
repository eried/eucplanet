package com.eried.eucplanet.tpms

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.EXTRA_HISTORY_METRICS
import com.eried.eucplanet.service.AlarmLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paired cap's reading has to leave the settings screen.
 *
 * For a while it did not. TpmsRepository decided which sensor spoke for the
 * tyre and published it, and the only thing that ever asked was the row that
 * had just been drawn: WheelData carried the wheel's own field straight from
 * the parser, so a rider with a cap on the valve had no alarm, no tile, no
 * history, no HUD - a sensor that could see a flat tyre and no way to say so.
 *
 * These tests hold the two halves of the fix: the repository publishes what
 * the surfaces need, and the surfaces read the presence of a sensor rather
 * than guessing it from a number.
 */
class TpmsReachesTheAppTest {

    private val now = 1_000_000L

    @Test fun `the cap outranks the wheel in the value every surface reads`() {
        val tpms = TpmsRepository()
        tpms.submitWheel(200f, now)
        tpms.submitPaired(360f, "AA:BB:CC:DD:EE:FF", now)

        val reading = tpms.current.value
        assertNotNull(reading)
        assertEquals(360f, reading!!.kpa, 0.01f)
        assertEquals(TpmsSource.PAIRED, reading.source)
    }

    @Test fun `the wheel keeps its own row while a cap answers for the tyre`() {
        // WheelData now carries whichever sensor is active, so the settings
        // section cannot read it to draw the WHEEL's row: with a cap paired it
        // would print the cap's pressure onto a wheel that has no sensor in
        // it. The repository publishes the wheel's own reading separately.
        val tpms = TpmsRepository()
        tpms.submitWheel(200f, now)
        tpms.submitPaired(360f, "AA:BB:CC:DD:EE:FF", now)

        assertEquals(360f, tpms.current.value!!.kpa, 0.01f)
        assertEquals(200f, tpms.wheelSensor.value!!.kpa, 0.01f)
    }

    @Test fun `a wheel that never had a sensor never grows a row`() {
        val tpms = TpmsRepository()
        tpms.submitPaired(360f, "AA:BB:CC:DD:EE:FF", now)
        assertNull("no wheel reading means no wheel row", tpms.wheelSensor.value)
    }

    @Test fun `the wheel's row ages out when the wheel stops reporting`() {
        val tpms = TpmsRepository()
        tpms.submitWheel(200f, now)
        assertNotNull(tpms.wheelSensor.value)
        tpms.refresh(now + TpmsPolicy.STALE_AFTER_MS + 1)
        assertNull("a stale reading must stop being drawn", tpms.wheelSensor.value)
    }

    @Test fun `history records a flat tyre and skips a wheel with no sensor`() {
        val sampler = EXTRA_HISTORY_METRICS.first { it.first == "TIRE_PRESSURE" }.second

        // Nothing measuring: no sample. Recording zero would draw a wheel that
        // has no sensor as a tyre that has been flat since the app started.
        assertNull(sampler(WheelData()))

        // A cap on a flat tyre: recorded, because it is a reading.
        assertEquals(0f, sampler(WheelData(tirePressureKpa = 0f, hasTirePressure = true)))

        assertEquals(360f, sampler(WheelData(tirePressureKpa = 360f, hasTirePressure = true)))
    }

    @Test fun `the alarm sees a flat tyre from a cap and nothing from a bare wheel`() {
        assertNull(AlarmLogic.tirePressureForAlarm(WheelData()))
        assertEquals(
            0f,
            AlarmLogic.tirePressureForAlarm(
                WheelData(tirePressureKpa = 0f, hasTirePressure = true)
            ),
        )
    }

    @Test fun `deleting the only cap hands the tyre back to the wheel`() {
        val tpms = TpmsRepository()
        val cap = "AA:BB:CC:DD:EE:FF"
        tpms.submitWheel(200f, now)
        tpms.submitPaired(360f, cap, now)
        assertEquals(360f, tpms.current.value!!.kpa, 0.01f)

        tpms.forget(cap, now)
        val after = tpms.current.value
        assertNotNull("the wheel still has a sensor", after)
        assertEquals(200f, after!!.kpa, 0.01f)
        assertEquals(TpmsSource.WHEEL, after.source)
        assertTrue(tpms.wheelIsActive.value)
    }
}
