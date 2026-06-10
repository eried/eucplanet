package com.eried.eucplanet.ble.transport

import com.eried.eucplanet.ble.BleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A discoverable BLE peripheral. [address] is an opaque platform handle
 *  (Android MAC string / iOS CBPeripheral UUID string). */
data class BleDevice(val address: String, val name: String?, val rssi: Int)

enum class BleConnState { Connecting, Connected, Disconnected, Failed }

/** A live BLE connection to a wheel, bound to one [BleProfile]'s characteristics. */
interface BleConnection {
    val state: StateFlow<BleConnState>
    /** Raw notification frames from the wheel's notify characteristic; the shared
     *  WheelAdapter parsers turn these into telemetry. */
    val incoming: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    fun close()
}

/**
 * Platform BLE I/O seam for the shared wheel layer. Android wraps BluetoothGatt;
 * iOS wraps CoreBluetooth. NOTE: the iOS Simulator has no Bluetooth radio — this
 * only does anything on a real iPhone.
 */
interface BleTransport {
    /** Scan for nearby BLE peripherals; cancel the collection to stop scanning. */
    fun scan(): Flow<BleDevice>
    /** Connect to [address] and bind to [profile]'s service + characteristics. */
    suspend fun connect(address: String, profile: BleProfile): BleConnection
}

/** Platform-provided transport. */
expect fun createBleTransport(): BleTransport
