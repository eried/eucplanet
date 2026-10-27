package com.eried.eucplanet.ble

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared GATT constants must stay the Android framework's values. */
class GattCharacteristicTest {
    @Test
    fun matchesAndroid() {
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, GattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, GattCharacteristic.WRITE_TYPE_DEFAULT)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, GattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE, GattCharacteristic.PROPERTY_WRITE)
    }
}
