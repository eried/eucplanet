package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charging on the wheels that never put a charge current on the wire.
 *
 * An InMotion V8S on the charger keeps reporting its own board draw, about
 * +0.02 A at 80 V. Integrating that produced the Battery screen's "Used 4 Wh"
 * against +54 % added: the wheel's standby consumption, presented as what three
 * hours of charging did.
 */
class ChargeWithoutCurrentTest {

    /** Three hours of charging as the V8S reports it: idle draw, nothing else. */
    private fun idleCharge(hours: Int, measuresChargeCurrent: Boolean): Float {
        val idleW = 80f * 0.02f
        var wh = 0f
        repeat(hours * 3600) {
            wh += ChargeEnergy.stepWh(
                prevPowerW = idleW,
                nowPowerW = idleW,
                dtMs = 1000L,
                dischargeIsPositivePower = true,   // InMotion V1
                charging = true,
                measuresChargeCurrent = measuresChargeCurrent,
            )
        }
        return wh
    }

    @Test
    fun `a wheel with no charge current integrates nothing while charging`() {
        assertEquals(0f, idleCharge(3, measuresChargeCurrent = false), 0.001f)
    }

    @Test
    fun `a wheel that does report one still integrates it`() {
        // Same trace on a family that measures its charge: the reading is real,
        // so it counts, and it counts as energy going in.
        assertTrue(idleCharge(3, measuresChargeCurrent = true) > 4f)
    }

    @Test
    fun `riding is integrated either way`() {
        // The rule is about the charge, not about the wheel. A ride's current is
        // real on every family, so nothing changes there.
        val ride = ChargeEnergy.stepWh(
            prevPowerW = 300f, nowPowerW = 300f, dtMs = 600_000L,
            dischargeIsPositivePower = true, charging = false,
            measuresChargeCurrent = false,
        )
        assertEquals(-50f, ride, 0.5f)
    }

    @Test
    fun `charged energy comes from the percentage and the pack size`() {
        // The rider's V8S: 32 % to 86 % on a 1000 Wh pack.
        assertEquals(540f, ChargeEnergy.chargedWhFromPercent(54f, 1000), 0.01f)
    }

    @Test
    fun `a ride that never charged reports nothing`() {
        // Percent gained is measured from the session low, and on a pack whose
        // percentage comes from voltage that reads several points on any ride:
        // it sags under a pull and comes back at a standstill. A rider mid-trip
        // was told their wheel had charged 50 Wh.
        assertEquals(0f, ChargeEnergy.chargedWhFromPercent(5f, 1000, sawCharge = false), 0.001f)
        assertEquals(50f, ChargeEnergy.chargedWhFromPercent(5f, 1000, sawCharge = true), 0.001f)
    }

    @Test
    fun `no pack size and no gain mean no figure`() {
        assertEquals(0f, ChargeEnergy.chargedWhFromPercent(54f, 0), 0.001f)
        assertEquals(0f, ChargeEnergy.chargedWhFromPercent(0f, 1000), 0.001f)
        assertEquals(0f, ChargeEnergy.chargedWhFromPercent(-3f, 1000), 0.001f)
    }
}
