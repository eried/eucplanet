package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.ble.BleDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSNumber
import platform.darwin.NSObject

/**
 * The CoreBluetooth side of [RadarScan], Android's RadarScanner: the radar
 * pairing scan on a central of its own, so it never disturbs the wheel's.
 * Every advertisement's name goes through the [adapters]; the one that claims
 * it tags the result.
 *
 * No service filter, as on Android: the Varia does not always put its
 * 128-bit service in the advert, so the name is the safer net. That scan
 * only sees anything with the app in front, which is where the pairing
 * screen is. The address is the peripheral's per-phone UUID, which is all iOS
 * gives (and what IosRadarLink connects to).
 */
class IosRadarScan(private val adapters: Set<RadarAdapter>) : RadarScan {

    /** Where the open scan's results go, null when none is open. */
    private var sink: ((RadarScanResult) -> Unit)? = null
    private val watchers = ArrayList<(Boolean) -> Unit>()
    private var wasOn: Boolean? = null

    private val delegate = Delegate()
    // Made with the graph, so CoreBluetooth has said whether it is on by the
    // time the rider taps Scan.
    private val central = CBCentralManager(delegate, null)

    override fun scan(): Flow<RadarScanResult> = callbackFlow {
        if (!isBluetoothEnabled()) throw IllegalStateException("Bluetooth not available")
        sink = { trySend(it) }
        central.scanForPeripheralsWithServices(null, null)
        awaitClose { stop() }
    }

    override fun isBluetoothEnabled(): Boolean = central.state == CBManagerStatePoweredOn

    override fun stop() {
        sink = null
        central.stopScan()
    }

    override fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit {
        watchers += listener
        return { watchers -= listener }
    }

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val on = isBluetoothEnabled()
            if (on == wasOn) return
            wasOn = on
            // The radio took the scan with it; the view model stops its side.
            if (!on) sink = null
            watchers.toList().forEach { it(on) }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val send = sink ?: return
            // Android reads the device's name; the advert's is the fallback
            // while iOS has none cached.
            val name = didDiscoverPeripheral.name
                ?: advertisementData[CBAdvertisementDataLocalNameKey] as? String
                ?: return
            val matched = adapters.firstOrNull { it.matches(name) } ?: return
            send(
                RadarScanResult(
                    device = BleDevice(
                        name = name,
                        address = didDiscoverPeripheral.identifier.UUIDString,
                        rssi = RSSI.intValue,
                    ),
                    vendor = matched.vendor,
                )
            )
        }
    }
}
