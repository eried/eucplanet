@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.cbUuid
import com.eried.eucplanet.ble.toCBUUID
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * The CoreBluetooth side of [ExternalGpsLink], Android's
 * ExternalGpsConnectionManager on iOS: a central of its own, so the wheel,
 * the radar and the GPS box each hold their own connection.
 *
 * As on Android: connect, discover the box's services, subscribe to the
 * adapter's notify characteristic, go CONNECTED once the subscription is
 * confirmed (live data flows even if the init fails), then write the init
 * frames to the adapter's write characteristic one at a time, each with a
 * response (for a RaceBox the time and position assist, for a Dragy the
 * 10 Hz rate), and once they drain read the adapter's poll characteristic
 * on its interval (a Dragy's battery on FD04), the first read 2 s later.
 * Every notification goes through the adapter's decoder; a box without the
 * expected service has its whole GATT written to the diagnostics log, once
 * per box, so a tester's log names its real profile.
 *
 * iOS negotiates the MTU itself (Android asks for 247; a RaceBox frame is
 * 88 or 100 bytes, which the adapter reassembles at any MTU) and has no
 * connection priority to raise.
 *
 * Auto-reconnect, as Android's BleAutoReconnector: after an unexpected drop
 * and when Bluetooth comes back the link connects again unless the rider
 * disconnected. A connect to a known peripheral waits until the box is in
 * range, in the background too (bluetooth-central); a box iOS has forgotten
 * is scanned for by the adapter's service.
 */
class IosExternalGpsLink : ExternalGpsLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<ExternalGpsSample>(extraBufferCapacity = 16)
    override val samples: SharedFlow<ExternalGpsSample> = _samples.asSharedFlow()

    private var activeAdapter: ExternalGpsAdapter? = null
    private var profile: ExternalGpsGattProfile = ExternalGpsGattProfile.NORDIC_UART
    private var currentAddress: String? = null
    private var currentInitWrites: List<ByteArray> = emptyList()

    /** False after the rider's disconnect: nothing reconnects until the next connect. */
    private var armed = false

    private var peripheral: CBPeripheral? = null
    private var scanningFor: String? = null
    private var servicesAwaitingCharacteristics = 0

    private var rx: CBCharacteristic? = null
    private var pollChar: CBCharacteristic? = null
    private val pendingInitWrites = ArrayDeque<ByteArray>()
    private var pollJob: Job? = null

    /** Address we last dumped the GATT for, so the reconnect loop logs once. */
    private var loggedGattFor: String? = null

    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private val central = CBCentralManager(centralDelegate, null)

    private fun bluetoothOn() = central.state == CBManagerStatePoweredOn

    // The repository calls in from Dispatchers.IO; CoreBluetooth and this
    // link's state live on the main thread, where the delegates run.
    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun connect(address: String, adapter: ExternalGpsAdapter, initWrites: List<ByteArray>) = onMain {
        activeAdapter = adapter
        profile = adapter.gattProfile()
        currentAddress = address
        currentInitWrites = initWrites
        armed = true
        connectInternal(address)
    }

    override fun disconnect() = onMain {
        armed = false
        currentAddress = null
        currentInitWrites = emptyList()
        activeAdapter = null
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun connectInternal(address: String) {
        teardown()
        // As Android: no connect while Bluetooth is off; it coming on does it.
        if (!bluetoothOn()) {
            Log.i(TAG, "connect($address) deferred: Bluetooth is off")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        pendingInitWrites.clear()
        pendingInitWrites.addAll(currentInitWrites)
        val p = knownPeripheral(address)
        _connectionState.value = ConnectionState.CONNECTING
        if (p == null) {
            Log.i(TAG, "GPS box $address not known to iOS; scanning for it")
            scanningFor = address
            central.scanForPeripheralsWithServices(listOf(profile.serviceUuid.toCBUUID()), null)
            return
        }
        Log.i(TAG, "Connecting to ${activeAdapter?.source?.displayName} @ $address (${currentInitWrites.size} init writes queued)")
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
        stopPolling()
        stopLookingFor()
        peripheral?.let { cancelLink(it) }
        peripheral = null
        rx = null
        pollChar = null
        pendingInitWrites.clear()
        servicesAwaitingCharacteristics = 0
    }

    private fun forceDisconnect() {
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun reconnectLater(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val address = currentAddress ?: return@launch
            if (!armed || !bluetoothOn()) return@launch
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

    /** As Android's disconnect() on a failed setup: the box is let go. */
    private fun failConnect(reason: String) {
        Log.e(TAG, reason)
        armed = false
        currentAddress = null
        activeAdapter = null
        teardown()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun kickInitWrite(p: CBPeripheral) {
        val c = rx ?: return
        val next = pendingInitWrites.removeFirstOrNull() ?: return
        // With a response, as Android: some boxes reject the other kind on a
        // characteristic that advertises both.
        p.writeValue(next.toNSData(), c, CBCharacteristicWriteWithResponse)
    }

    private fun startPolling() {
        val p = peripheral ?: return
        val c = pollChar ?: return
        if (pollJob != null) return
        val interval = activeAdapter?.pollIntervalMs() ?: return
        pollJob = scope.launch {
            delay(POLL_INITIAL_DELAY_MS)
            while (true) {
                p.readValueForCharacteristic(c)
                delay(interval)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun logDiscoveredGatt(p: CBPeripheral) {
        val addr = currentAddress
        if (addr != null && addr == loggedGattFor) return
        loggedGattFor = addr
        val src = activeAdapter?.source?.displayName ?: "GPS"
        DiagnosticsLogger.note("extgps: $src ($addr) missing expected service ${profile.serviceUuid}; its GATT profile:")
        for (svc in p.services.orEmpty().map { it as CBService }) {
            DiagnosticsLogger.note("  service ${svc.UUID.cbUuid()}")
            for (ch in svc.characteristics.orEmpty().map { it as CBCharacteristic }) {
                val props = ch.properties
                val flags = buildString {
                    if (props and CBCharacteristicPropertyNotify != 0uL) append('N')
                    if (props and CBCharacteristicPropertyIndicate != 0uL) append('I')
                    if (props and CBCharacteristicPropertyRead != 0uL) append('R')
                    if (props and CBCharacteristicPropertyWrite != 0uL) append('W')
                    if (props and CBCharacteristicPropertyWriteWithoutResponse != 0uL) append('w')
                }
                DiagnosticsLogger.note("    char ${ch.UUID.cbUuid()} [$flags]")
            }
        }
    }

    /** Every service's characteristics are in: find the box's and subscribe. */
    private fun onGattReady(p: CBPeripheral) {
        val adapter = activeAdapter ?: return
        val services = p.services.orEmpty().map { it as CBService }
        val all = services.flatMap { s -> s.characteristics.orEmpty().map { it as CBCharacteristic } }
        val service = services.firstOrNull { it.UUID.cbUuid() == profile.serviceUuid }
        val chars = service?.characteristics.orEmpty().map { it as CBCharacteristic }
        val tx = chars.firstOrNull { it.UUID.cbUuid() == profile.notifyUuid }
        if (tx == null) {
            Log.e(TAG, "Notify characteristic ${profile.notifyUuid} not found on ${profile.serviceUuid}")
            logDiscoveredGatt(p)
            failConnect("GPS box without its notify characteristic")
            return
        }
        rx = chars.firstOrNull { it.UUID.cbUuid() == profile.writeUuid }
        // Some boxes keep the status characteristic on another service.
        pollChar = adapter.pollCharacteristic()?.let { uuid ->
            chars.firstOrNull { it.UUID.cbUuid() == uuid } ?: all.firstOrNull { it.UUID.cbUuid() == uuid }
        }
        p.setNotifyValue(true, tx)
    }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (bluetoothOn()) {
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
            Log.i(TAG, "Found GPS box $scanningFor; connecting")
            stopLookingFor()
            open(didDiscoverPeripheral)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (!isOurs(didConnectPeripheral)) return
            Log.i(TAG, "GATT connected; discovering services")
            _connectionState.value = ConnectionState.INITIALIZING
            // All of them: the poll characteristic may sit on another service,
            // and an unknown box's profile is logged whole.
            didConnectPeripheral.discoverServices(null)
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
            Log.i(TAG, "GATT disconnected: ${error?.localizedDescription ?: "by request"}")
            peripheral = null
            teardown()
            _connectionState.value = ConnectionState.DISCONNECTED
            // As Android: only an unexpected drop comes back by itself.
            if (armed && error != null) reconnectLater(2_000)
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                failConnect("Service discovery failed: ${didDiscoverServices.localizedDescription}")
                return
            }
            val services = peripheral.services.orEmpty().map { it as CBService }
            if (services.isEmpty()) {
                onGattReady(peripheral)
                return
            }
            servicesAwaitingCharacteristics = services.size
            services.forEach { peripheral.discoverCharacteristics(null, it) }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) Log.w(TAG, "Characteristics of ${didDiscoverCharacteristicsForService.UUID.cbUuid()}: ${error.localizedDescription}")
            servicesAwaitingCharacteristics--
            if (servicesAwaitingCharacteristics == 0) onGattReady(peripheral)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (didUpdateNotificationStateForCharacteristic.UUID.cbUuid() != profile.notifyUuid) return
            if (error != null) {
                failConnect("Failed to enable notifications on the notify characteristic: ${error.localizedDescription}")
                return
            }
            Log.i(TAG, "Notify on; ${pendingInitWrites.size} init write(s) queued")
            // Connected as soon as notifications are wired, as on Android.
            _connectionState.value = ConnectionState.CONNECTED
            if (rx == null) pendingInitWrites.clear()
            kickInitWrite(peripheral)
            // No init writes left to send: the poll (if any) can start now.
            if (pendingInitWrites.isEmpty()) startPolling()
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (didWriteValueForCharacteristic.UUID.cbUuid() != profile.writeUuid) return
            if (error != null) {
                Log.w(TAG, "Init write failed (${error.localizedDescription}); skipping remaining ${pendingInitWrites.size}")
                pendingInitWrites.clear()
                startPolling()
                return
            }
            kickInitWrite(peripheral)
            // Last write sent: kick off the periodic read (if any).
            if (pendingInitWrites.isEmpty()) startPolling()
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
            val adapter = activeAdapter ?: return
            when (didUpdateValueForCharacteristic.UUID.cbUuid()) {
                profile.notifyUuid -> adapter.decode(bytes)?.let { _samples.tryEmit(it) }
                adapter.pollCharacteristic() -> adapter.onPollResult(bytes)
            }
        }
    }

    private companion object {
        const val TAG = "IosExtGpsLink"
        /** Before the first periodic read, as Android, clear of the last init write. */
        const val POLL_INITIAL_DELAY_MS = 2_000L
    }
}
