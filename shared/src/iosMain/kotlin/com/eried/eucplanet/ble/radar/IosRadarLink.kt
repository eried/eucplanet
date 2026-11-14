@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.cbUuid
import com.eried.eucplanet.ble.toCBUUID
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import kotlin.uuid.Uuid

/**
 * The CoreBluetooth side of [RadarLink], Android's RadarConnectionManager
 * on iOS: a central of its own, so the wheel and the radar never share a
 * connection. Connect, discover the adapter's service and the standard
 * battery service, subscribe to the adapter's notify characteristic and to
 * the battery level when it notifies, go CONNECTED once every subscription is
 * confirmed, then read the battery once so the card has a value straight
 * away. Every notification (and that read) is handed on with its
 * characteristic.
 *
 * Auto-reconnect, as Android's BleAutoReconnector: after an unexpected drop,
 * and when Bluetooth comes back, the link connects again unless the rider
 * disconnected. Android scans for the radar first; CoreBluetooth needs no
 * scan, since a connect to a known peripheral waits until the radar is in
 * range, in the background too (UIBackgroundModes bluetooth-central). Only a
 * radar iOS does not know any more is scanned for, filtered by the adapter's
 * service so that scan also works in the background.
 *
 * iOS picks the MTU itself (Android asks for 100), and the Varia caps a frame
 * at 20 bytes anyway, so there is nothing to ask for here.
 */
class IosRadarLink : RadarLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<RadarNotification>(extraBufferCapacity = 16)
    override val notifications: SharedFlow<RadarNotification> = _notifications.asSharedFlow()

    private var activeAdapter: RadarAdapter? = null
    private var currentAddress: String? = null

    /** False after the rider's disconnect: nothing reconnects until the next connect. */
    private var armed = false

    private var peripheral: CBPeripheral? = null

    /** Looking for a radar iOS has forgotten, by its service. */
    private var scanningFor: String? = null

    /** Services whose characteristics have not been reported yet. */
    private var servicesAwaitingCharacteristics = 0

    /** Characteristics asked to notify and not yet confirmed. */
    private val pendingSubscriptions = HashSet<String>()
    private var batteryChar: CBCharacteristic? = null

    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private val central = CBCentralManager(centralDelegate, null)

    private fun bluetoothOn() = central.state == CBManagerStatePoweredOn

    // The repository calls in from Dispatchers.IO; CoreBluetooth and this
    // link's state live on the main thread, where the delegates run.
    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun connect(address: String, adapter: RadarAdapter) = onMain {
        activeAdapter = adapter
        currentAddress = address
        armed = true
        connectInternal(address)
    }

    override fun disconnect() = onMain {
        armed = false
        currentAddress = null
        activeAdapter = null
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** (Re)connect with the remembered adapter; leaves the reconnect armed. */
    private fun connectInternal(address: String) {
        teardown()
        // As Android: no connect while Bluetooth is off; it coming on does it.
        if (!bluetoothOn()) {
            Log.i(TAG, "connect($address) deferred: Bluetooth is off")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val p = knownPeripheral(address)
        _connectionState.value = ConnectionState.CONNECTING
        if (p == null) {
            // Not known to iOS any more (Bluetooth reset, or the cache
            // dropped it): find it by its service, then connect.
            Log.i(TAG, "Radar $address not known to iOS; scanning for it")
            scanningFor = address
            val service = activeAdapter?.serviceUuid?.toCBUUID()
            central.scanForPeripheralsWithServices(service?.let { listOf(it) }, null)
            return
        }
        Log.i(TAG, "Connecting to ${activeAdapter?.vendor?.displayName} @ $address")
        open(p)
    }

    private fun open(p: CBPeripheral) {
        peripheral = p
        p.delegate = peripheralDelegate
        central.connectPeripheral(p, null)
    }

    private fun knownPeripheral(address: String): CBPeripheral? {
        val id = runCatching { NSUUID(uUIDString = address) }.getOrNull() ?: return null
        return central.retrievePeripheralsWithIdentifiers(listOf(id)).firstOrNull() as? CBPeripheral
    }

    private fun stopLookingFor() {
        if (scanningFor != null) central.stopScan()
        scanningFor = null
    }

    private fun teardown() {
        stopLookingFor()
        peripheral?.let { cancelLink(it) }
        peripheral = null
        batteryChar = null
        pendingSubscriptions.clear()
        servicesAwaitingCharacteristics = 0
    }

    /** Bluetooth went off: drop the dead link, keep the radar to come back to. */
    private fun forceDisconnect() {
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** A reconnect a beat later, as Android's reconnector waits for the stack. */
    private fun reconnectLater(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val address = currentAddress ?: return@launch
            if (!armed || !bluetoothOn()) return@launch
            // The repository's own loop may have reconnected in the meantime.
            if (_connectionState.value != ConnectionState.DISCONNECTED) return@launch
            connectInternal(address)
        }
    }

    private fun isOurs(p: CBPeripheral): Boolean =
        peripheral?.identifier?.UUIDString == p.identifier.UUIDString

    /** Links this app cancelled itself, whose "disconnected" report is not news. */
    private val cancelledLinks = mutableMapOf<String, Int>()

    private fun cancelLink(p: CBPeripheral) {
        val id = p.identifier.UUIDString
        cancelledLinks[id] = (cancelledLinks[id] ?: 0) + 1
        central.cancelPeripheralConnection(p)
    }

    private fun consumeCancelled(p: CBPeripheral, error: NSError?): Boolean {
        if (error != null) return false
        val id = p.identifier.UUIDString
        val n = cancelledLinks[id] ?: return false
        if (n <= 1) cancelledLinks.remove(id) else cancelledLinks[id] = n - 1
        return true
    }

    /** As Android: a radar without the adapter's characteristic is let go. */
    private fun failConnect(reason: String) {
        Log.e(TAG, reason)
        armed = false
        currentAddress = null
        activeAdapter = null
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun maybeConnected(p: CBPeripheral) {
        if (servicesAwaitingCharacteristics > 0 || pendingSubscriptions.isNotEmpty()) return
        if (_connectionState.value != ConnectionState.INITIALIZING) return
        _connectionState.value = ConnectionState.CONNECTED
        Log.i(TAG, "Radar connected")
        // A one-shot battery read, so the card has a value even when the
        // radar only notifies on a change.
        batteryChar?.let { p.readValueForCharacteristic(it) }
    }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (bluetoothOn()) {
                // The stack is not ready the instant it says on.
                if (armed && _connectionState.value == ConnectionState.DISCONNECTED) reconnectLater(1_500)
            } else if (peripheral != null || scanningFor != null ||
                _connectionState.value != ConnectionState.DISCONNECTED
            ) {
                Log.i(TAG, "Bluetooth turned off; forcing disconnect")
                forceDisconnect()
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            if (didDiscoverPeripheral.identifier.UUIDString != scanningFor) return
            Log.i(TAG, "Found radar $scanningFor; connecting")
            stopLookingFor()
            open(didDiscoverPeripheral)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (!isOurs(didConnectPeripheral)) return
            Log.i(TAG, "Link up; discovering services")
            _connectionState.value = ConnectionState.INITIALIZING
            val adapter = activeAdapter ?: return failConnect("Connected without an adapter")
            didConnectPeripheral.discoverServices(listOf(adapter.serviceUuid.toCBUUID(), BATTERY_SERVICE.toCBUUID()))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            if (consumeCancelled(didFailToConnectPeripheral, error)) return
            if (!isOurs(didFailToConnectPeripheral)) return
            Log.w(TAG, "Link failed: ${error?.localizedDescription}")
            peripheral = null
            teardown()
            _connectionState.value = ConnectionState.DISCONNECTED
            if (armed) reconnectLater(2_000)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            if (consumeCancelled(didDisconnectPeripheral, error)) return
            if (!isOurs(didDisconnectPeripheral)) return
            Log.i(TAG, "Link down: ${error?.localizedDescription ?: "by request"}")
            peripheral = null
            teardown()
            _connectionState.value = ConnectionState.DISCONNECTED
            // As Android: only an unexpected drop comes back by itself.
            if (armed && error != null) reconnectLater(2_000)
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val adapter = activeAdapter
            if (didDiscoverServices != null || adapter == null) {
                failConnect("Service discovery failed: ${didDiscoverServices?.localizedDescription}")
                return
            }
            val services = peripheral.services.orEmpty().map { it as CBService }
            val radar = services.firstOrNull { it.UUID.cbUuid() == adapter.serviceUuid }
            if (radar == null) {
                failConnect("Radar service ${adapter.serviceUuid} not found")
                return
            }
            val battery = services.firstOrNull { it.UUID.cbUuid() == BATTERY_SERVICE }
            servicesAwaitingCharacteristics = if (battery != null) 2 else 1
            peripheral.discoverCharacteristics(listOf(adapter.notifyCharacteristicUuid.toCBUUID()), radar)
            battery?.let { peripheral.discoverCharacteristics(listOf(BATTERY_LEVEL.toCBUUID()), it) }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val adapter = activeAdapter ?: return
            val service = didDiscoverCharacteristicsForService
            val chars = service.characteristics.orEmpty().map { it as CBCharacteristic }
            if (service.UUID.cbUuid() == adapter.serviceUuid) {
                val notify = chars.firstOrNull { it.UUID.cbUuid() == adapter.notifyCharacteristicUuid }
                if (notify == null || error != null) {
                    failConnect("Radar notify characteristic ${adapter.notifyCharacteristicUuid} not found")
                    return
                }
                pendingSubscriptions += notify.UUID.UUIDString
                peripheral.setNotifyValue(true, notify)
            } else {
                // The battery service is optional, as on Android: no level, no subscription.
                val level = chars.firstOrNull { it.UUID.cbUuid() == BATTERY_LEVEL }
                batteryChar = level
                if (level != null && (level.properties and CBCharacteristicPropertyNotify) != 0uL) {
                    pendingSubscriptions += level.UUID.UUIDString
                    peripheral.setNotifyValue(true, level)
                }
            }
            servicesAwaitingCharacteristics--
            maybeConnected(peripheral)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val ch = didUpdateNotificationStateForCharacteristic
            if (error != null && ch.UUID.cbUuid() == activeAdapter?.notifyCharacteristicUuid) {
                failConnect("Failed to enable notify on radar characteristic: ${error.localizedDescription}")
                return
            }
            if (error != null) Log.w(TAG, "Battery notify refused: ${error.localizedDescription}")
            pendingSubscriptions -= ch.UUID.UUIDString
            maybeConnected(peripheral)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) return
            val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            if (bytes.isEmpty()) return
            _notifications.tryEmit(RadarNotification(didUpdateValueForCharacteristic.UUID.cbUuid(), bytes))
        }
    }

    private companion object {
        const val TAG = "IosRadarLink"

        /** Standard org.bluetooth.service.battery_service. */
        val BATTERY_SERVICE: Uuid = Uuid.parse("0000180f-0000-1000-8000-00805f9b34fb")

        /** Standard org.bluetooth.characteristic.battery_level. */
        val BATTERY_LEVEL: Uuid = Uuid.parse("00002a19-0000-1000-8000-00805f9b34fb")
    }
}
