package com.eried.eucplanet.ble.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSNumber
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
actual fun createBleTransport(): BleTransport = IosBleTransport()

/** CoreBluetooth scanner. CBCentralManager starts scanning once the radio powers
 *  on; each discovery is forwarded to the [scan] flow. */
@OptIn(ExperimentalForeignApi::class)
private class IosBleTransport : BleTransport {
    override fun scan(): Flow<BleDevice> = callbackFlow {
        val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {
            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                if (central.state == CBManagerStatePoweredOn) {
                    central.scanForPeripheralsWithServices(null, null)
                }
            }

            override fun centralManager(
                central: CBCentralManager,
                didDiscoverPeripheral: CBPeripheral,
                advertisementData: Map<Any?, *>,
                RSSI: NSNumber,
            ) {
                val id = didDiscoverPeripheral.identifier.UUIDString
                trySend(BleDevice(id, didDiscoverPeripheral.name, RSSI.intValue))
            }
        }
        val manager = CBCentralManager(delegate, null)
        awaitClose { manager.stopScan() }
    }
}
