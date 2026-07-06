package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the V14 displayed state-of-charge: it is the HIGHER of the two banks
 * (matching the InMotion app), not the average. The wheel reports two
 * separately-tracked bank percentages at frame offsets 34/36 that sit ~2-3%
 * apart and do not converge, so averaging them (what we and WheelLog did) read
 * ~2% low versus the manufacturer app, which shows the higher/primary bank.
 */
class InMotionV2BatteryTest {

    /** 88-byte V14 telemetry frame with the two bank percentages set (uint16 LE,
     *  hundredths of a percent at offsets 34 and 36); everything else zero. */
    private fun frameWithBanks(bank1CentiPct: Int, bank2CentiPct: Int): ByteArray {
        val f = ByteArray(88)
        f[34] = (bank1CentiPct and 0xFF).toByte()
        f[35] = ((bank1CentiPct shr 8) and 0xFF).toByte()
        f[36] = (bank2CentiPct and 0xFF).toByte()
        f[37] = ((bank2CentiPct shr 8) and 0xFF).toByte()
        return f
    }

    @Test fun `displayed battery is the higher bank, not the average`() {
        // Real capture: bank1 = 39.38 %, bank2 = 36.18 % (avg 37.78 -> old code
        // showed 38). The higher bank rounds to 39.
        val wd = InMotionV2Parser.parseTelemetry(frameWithBanks(3938, 3618))!!
        assertEquals(39.38f, wd.battery1Percent, 0.001f)
        assertEquals(36.18f, wd.battery2Percent, 0.001f)
        assertEquals(39, wd.batteryPercent)   // max(39.38, 36.18) rounded, not the avg (38)
    }

    @Test fun `bank order does not matter`() {
        assertEquals(39, InMotionV2Parser.parseTelemetry(frameWithBanks(3618, 3938))!!.batteryPercent)
    }

    @Test fun `equal banks round half up`() {
        // 80.50 % on both -> 81 (round half toward +inf), same as before when balanced.
        assertEquals(81, InMotionV2Parser.parseTelemetry(frameWithBanks(8050, 8050))!!.batteryPercent)
    }
}
