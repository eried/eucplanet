package com.eried.eucplanet.ble.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android keeps using the existing `:app` `BleConnectionManager` for now; the
 * shared transport's Android actual will wrap it (BluetoothGatt) in a later step
 * when the connection flow is unified. Stub so the shared module compiles for
 * the Android target.
 */
actual fun createBleTransport(): BleTransport = object : BleTransport {
    override fun scan(): Flow<BleDevice> = emptyFlow()
}
