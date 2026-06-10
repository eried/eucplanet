package com.eried.eucplanet.ble.transport

import kotlinx.coroutines.flow.Flow

/** A discoverable BLE peripheral. [address] is an opaque platform handle
 *  (Android MAC string / iOS CBPeripheral UUID string). */
data class BleDevice(val address: String, val name: String?, val rssi: Int)

/**
 * Platform BLE I/O seam for the shared wheel layer. Android wraps BluetoothGatt;
 * iOS wraps CoreBluetooth (CBCentralManager/CBPeripheral). The shared
 * WheelAdapter parsers consume the bytes this surfaces.
 *
 * NOTE: the iOS Simulator has no Bluetooth radio — [scan] only yields devices on
 * a real iPhone.
 */
interface BleTransport {
    /** Scan for nearby BLE peripherals; cancel the collection to stop scanning. */
    fun scan(): Flow<BleDevice>
}

/** Platform-provided transport. */
expect fun createBleTransport(): BleTransport
