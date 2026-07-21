package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * Drift-guard for the [BleProfile] catalogue. The connection manager resolves
 * the write characteristic from [BleProfile.effectiveWriteServiceUuid]; if the
 * InMotion V1 split-service wiring is dropped or mis-set the write char lookup
 * lands on the wrong service, returns null, and NO InMotion V1 wheel (V5 / V8 /
 * V10 / V10F / L6) can connect. These tests lock the two-service shape in.
 */
class BleProfileTest {

    private fun uuid(short: String) =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    @Test fun `InMotion V1 splits notify and write across two services`() {
        val p = BleProfile.INMOTION_V1
        assertEquals("notify service is 0xFFE0", uuid("ffe0"), p.serviceUuid)
        assertEquals("notify char is 0xFFE4", uuid("ffe4"), p.notifyCharacteristic)
        assertEquals("write char is 0xFFE9", uuid("ffe9"), p.writeCharacteristic)
        // The whole point: the write char lives under its OWN service 0xFFE5.
        assertEquals("write service is 0xFFE5", uuid("ffe5"), p.effectiveWriteServiceUuid)
        assertNotEquals(
            "write service must differ from the notify service, else the write " +
                "char lookup fails and V1 wheels can't connect",
            p.serviceUuid, p.effectiveWriteServiceUuid
        )
    }

    @Test fun `single-service profiles resolve the write char on their own service`() {
        // Nordic UART (V2) and HM-10 (KingSong / Begode / Veteran) keep notify
        // and write on one service, so the effective write service is the same.
        for (p in listOf(BleProfile.NORDIC_UART, BleProfile.HM10)) {
            assertEquals(p.serviceUuid, p.effectiveWriteServiceUuid)
        }
    }
}
