package com.eried.eucplanet.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wheel's saved address is dialled on every start. One that the
 * Bluetooth stack rejects used to throw there and close the app each time.
 */
class BluetoothAddressTest {

    @Test
    fun `a real address is accepted`() {
        assertTrue(isBluetoothAddress("AA:BB:CC:11:22:33"))
        assertTrue(isBluetoothAddress("00:00:00:00:00:00"))
    }

    @Test
    fun `what the stack would throw on is rejected`() {
        assertFalse(isBluetoothAddress(null))
        assertFalse(isBluetoothAddress(""))
        // Lower case is refused by Android too.
        assertFalse(isBluetoothAddress("aa:bb:cc:11:22:33"))
        assertFalse(isBluetoothAddress("AA:BB:CC:11:22"))
        assertFalse(isBluetoothAddress("AA:BB:CC:11:22:33:44"))
        assertFalse(isBluetoothAddress("AA-BB-CC-11-22-33"))
        assertFalse(isBluetoothAddress("AABBCC112233"))
        assertFalse(isBluetoothAddress("GG:BB:CC:11:22:33"))
        assertFalse(isBluetoothAddress(" AA:BB:CC:11:22:33"))
        assertFalse(isBluetoothAddress("AA:BB:CC:11:22:33\n"))
        // The simulator's wheels are handled before any dialling.
        assertFalse(isBluetoothAddress("VIRTUAL:V14"))
    }
}
