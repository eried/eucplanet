package com.eried.eucplanet.ble.transport

import com.eried.eucplanet.ble.BleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android keeps using the existing `:app` `BleConnectionManager` (BluetoothGatt)
 * for now; the shared transport's Android actual will wrap it in a later step
 * when the connection flow is unified. Stub so the shared module compiles for
 * the Android target.
 */
actual fun createBleTransport(): BleTransport = object : BleTransport {
    override fun scan(): Flow<BleDevice> = emptyFlow()
    override suspend fun connect(address: String, profile: BleProfile): BleConnection =
        throw UnsupportedOperationException("Android uses :app BleConnectionManager")
}
