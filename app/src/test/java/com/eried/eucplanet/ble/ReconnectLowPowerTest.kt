package com.eried.eucplanet.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reconnect hunt scans at full speed for 10 minutes, then drops to low power. */
class ReconnectLowPowerTest {

    private val start = 5_000_000L

    @Test fun `still full speed at 9 min 59 s`() {
        assertFalse(BleConnectionManager.reconnectLowPowerDue(start + 9 * 60_000L + 59_000L, start))
    }

    @Test fun `low power from exactly 10 min`() {
        assertTrue(BleConnectionManager.reconnectLowPowerDue(start + 10 * 60_000L, start))
    }

    @Test fun `a hunt that just began is full speed`() {
        assertFalse(BleConnectionManager.reconnectLowPowerDue(start, start))
    }
}
