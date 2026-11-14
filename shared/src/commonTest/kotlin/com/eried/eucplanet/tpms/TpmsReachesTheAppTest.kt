package com.eried.eucplanet.tpms

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.service.AlarmLogic
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

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
        assertEquals(360f, reading!!.kpa, absoluteTolerance = 0.01f)
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

        assertEquals(360f, tpms.current.value!!.kpa, absoluteTolerance = 0.01f)
        assertEquals(200f, tpms.wheelSensor.value!!.kpa, absoluteTolerance = 0.01f)
    }

    @Test fun `a wheel that never had a sensor never grows a row`() {
        val tpms = TpmsRepository()
        tpms.submitPaired(360f, "AA:BB:CC:DD:EE:FF", now)
        assertNull(tpms.wheelSensor.value, "no wheel reading means no wheel row")
    }

    @Test fun `the wheel's row ages out when the wheel stops reporting`() {
        val tpms = TpmsRepository()
        tpms.submitWheel(200f, now)
        assertNotNull(tpms.wheelSensor.value)
        tpms.refresh(now + TpmsPolicy.STALE_AFTER_MS + 1)
        assertNull(tpms.wheelSensor.value, "a stale reading must stop being drawn")
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
        assertEquals(360f, tpms.current.value!!.kpa, absoluteTolerance = 0.01f)

        tpms.forget(cap, now)
        val after = tpms.current.value
        assertNotNull(after, "the wheel still has a sensor")
        assertEquals(200f, after!!.kpa, absoluteTolerance = 0.01f)
        assertEquals(TpmsSource.WHEEL, after.source)
        assertTrue(tpms.wheelIsActive.value)
    }
}
