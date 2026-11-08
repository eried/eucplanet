@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.virtual.VirtualWheel
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.uuid.Uuid

/** A wheel seen by the scan. [address] is the CoreBluetooth peripheral id. */
data class ScannedWheel(val address: String, val name: String?, val rssi: Int)

/**
 * The CoreBluetooth side of [WheelLink]. Same contract as Android's
 * BleConnectionManager: route the adapter by name, re-route by discovered
 * services, subscribe to notify, go CONNECTED, decode every notification
 * through the adapter, and drain writes through the shared [BleWriteQueue].
 *
 * Virtual wheels ("VIRTUAL:<id>") run without the radio, so the simulator
 * can exercise the whole path.
 *
 * Not here yet (Android has them): auto-reconnect with a filtered scan,
 * the status-133 retries (Android-only), RSSI polling.
 */
class IosWheelLink(private val adapter: WheelAdapter) : WheelLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    private val _connectedAddress = MutableStateFlow<String?>(null)
    override val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()
    private val _connectedBrand = MutableStateFlow<String?>(null)
    override val connectedBrand: StateFlow<String?> = _connectedBrand.asStateFlow()
    private val _decodedResults = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 256)
    override val decodedResults: SharedFlow<DecodeResult> = _decodedResults.asSharedFlow()
    private val _rssiDbm = MutableStateFlow<Int?>(null)
    override val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()
    private val _connectedSinceMs = MutableStateFlow(0L)
    override val connectedSinceMs: StateFlow<Long> = _connectedSinceMs.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedWheel>>(emptyList())
    /** Wheels seen since [startScan], strongest first. */
    val scanResults: StateFlow<List<ScannedWheel>> = _scanResults.asStateFlow()
    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()
    private val _radio = MutableStateFlow(BluetoothRadio.UNKNOWN)
    /** The radio as CoreBluetooth reports it, for the "needs attention" list. */
    val radio: StateFlow<BluetoothRadio> = _radio.asStateFlow()

    private val writeQueue = BleWriteQueue()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private var writerJob: Job? = null

    private var currentName: String? = null
    private var peripheral: CBPeripheral? = null
    private var writeChar: CBCharacteristic? = null
    private var notifyChar: CBCharacteristic? = null
    private var writeType = GattCharacteristic.WRITE_TYPE_DEFAULT
    private var pendingWriteAck: Channel<Boolean>? = null
    private var virtualWheel: VirtualWheel? = null
    private var virtualTickJob: Job? = null
    private var wantScan = false

    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private val central = CBCentralManager(centralDelegate, null)

    fun startScan() {
        wantScan = true
        _scanResults.value = emptyList()
        if (_bluetoothOn.value) central.scanForPeripheralsWithServices(null, null)
    }

    fun stopScan() {
        wantScan = false
        central.stopScan()
    }

    override fun connectedAddressOrNull(): String? =
        if (_connectionState.value == ConnectionState.CONNECTED) _connectedAddress.value else null

    override fun refreshBrand() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) _connectedBrand.value = adapter.brand
    }

    override fun connect(address: String, name: String?, isAuto: Boolean) {
        disconnect()
        currentName = name
        _connectedAddress.value = address
        VirtualWheelRegistry.parsePseudoAddress(address)?.let { connectVirtual(it); return }

        val p = peripherals[address]
            ?: (central.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = address)))
                .firstOrNull() as? CBPeripheral)
        if (p == null) {
            Log.e(TAG, "Unknown peripheral $address")
            _connectedAddress.value = null
            return
        }
        stopScan()
        DiagnosticsLogger.note("Connect requested: name=${name ?: "(unknown)"} addr=$address")
        adapter.notifyConnectingTo(name)?.let { _decodedResults.tryEmit(it) }
        _connectedBrand.value = adapter.brand
        _connectedDeviceName.value = name
        _connectionState.value = ConnectionState.CONNECTING
        peripheral = p
        p.delegate = peripheralDelegate
        central.connectPeripheral(p, null)
    }

    override fun disconnect() {
        virtualTickJob?.cancel()
        virtualTickJob = null
        virtualWheel = null
        writerJob?.cancel()
        writerJob = null
        writeQueue.clear()
        peripheral?.let { central.cancelPeripheralConnection(it) }
        peripheral = null
        writeChar = null
        notifyChar = null
        if (_connectionState.value != ConnectionState.DISCONNECTED) adapter.onDisconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedAddress.value = null
        _connectedDeviceName.value = null
        _connectedBrand.value = null
        _connectedSinceMs.value = 0L
        _rssiDbm.value = null
    }

    override fun writeCommand(data: ByteArray, kind: BleWriteQueue.Kind) {
        if (data.isEmpty()) return
        DiagnosticsLogger.tx(data)
        if (!writeQueue.offer(kind, data)) {
            DiagnosticsLogger.note("Write DROPPED (${data.size}B) - the command queue is full")
        }
        writeSignal.trySend(Unit)
    }

    override fun suppressAutoConnect() = stopScan()

    override fun resumeAutoConnect(reconnectAddress: String?, reconnectName: String?) {
        if (reconnectAddress != null && _connectionState.value == ConnectionState.DISCONNECTED) {
            connect(reconnectAddress, reconnectName, isAuto = true)
        }
    }

    private fun connectVirtual(id: String) {
        val wheel = VirtualWheelRegistry.create(id) ?: return
        wheel.reset()
        virtualWheel = wheel
        currentName = wheel.bleName.takeIf { it.isNotEmpty() }
        _connectedDeviceName.value = "${wheel.displayName} (virtual)"
        _connectionState.value = ConnectionState.CONNECTING
        currentName?.let { n -> adapter.notifyConnectingTo(n)?.let { _decodedResults.tryEmit(it) } }
        _connectedBrand.value = adapter.brand
        scope.launch {
            delay(150)
            _connectionState.value = ConnectionState.INITIALIZING
            for (resp in wheel.onConnect()) emitNotification(resp)
            delay(150)
            markConnected()
        }
        val tickStart = System.currentTimeMillis()
        virtualTickJob = scope.launch {
            while (true) {
                delay(100)
                val w = virtualWheel ?: break
                for (resp in w.onTick(System.currentTimeMillis() - tickStart)) emitNotification(resp)
            }
        }
    }

    private fun markConnected() {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTED
        _connectedSinceMs.value = System.currentTimeMillis()
        DiagnosticsLogger.note("Connected: name=${currentName ?: "(none)"} adapter=${adapter.familyDisplayName}")
        writerJob = scope.launch { drainWrites() }
    }

    private fun emitNotification(bytes: ByteArray) {
        DiagnosticsLogger.rx(bytes)
        for (result in adapter.onRawNotification(bytes)) _decodedResults.tryEmit(result)
    }

    /** One write at a time, retried per [BleWriteQueue] policy, like the Android writer. */
    private suspend fun drainWrites() {
        while (true) {
            val entry = writeQueue.take()
            if (entry == null) {
                writeSignal.receive()
                continue
            }
            virtualWheel?.let { w ->
                for (resp in w.onWrite(entry.data)) emitNotification(resp)
                continue
            }
            val p = peripheral ?: return
            val c = writeChar ?: return
            val maxAttempts = BleWriteQueue.maxAttempts(entry.kind)
            for (attempt in 1..maxAttempts) {
                if (writeType == GattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                    p.writeValue(entry.data.toNSData(), c, CBCharacteristicWriteWithoutResponse)
                    // CoreBluetooth gives no callback for these. Android waits up to
                    // 200 ms for its local one; a short gap keeps HM-10 modules paced.
                    delay(NO_RESPONSE_GAP_MS)
                    break
                }
                val ack = Channel<Boolean>(1)
                pendingWriteAck = ack
                p.writeValue(entry.data.toNSData(), c, CBCharacteristicWriteWithResponse)
                val ok = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.receive() } ?: false
                pendingWriteAck = null
                if (ok) break
                if (attempt < maxAttempts) delay(BleWriteQueue.retryDelayMs(attempt))
            }
        }
    }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            _bluetoothOn.value = central.state == CBManagerStatePoweredOn
            _radio.value = when (central.state) {
                CBManagerStatePoweredOn -> BluetoothRadio.ON
                CBManagerStatePoweredOff -> BluetoothRadio.OFF
                CBManagerStateUnauthorized -> BluetoothRadio.NOT_ALLOWED
                CBManagerStateUnsupported -> BluetoothRadio.UNSUPPORTED
                else -> BluetoothRadio.UNKNOWN
            }
            if (_bluetoothOn.value && wantScan) central.scanForPeripheralsWithServices(null, null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val name = didDiscoverPeripheral.name ?: return
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            val seen = ScannedWheel(id, name, RSSI.intValue)
            _scanResults.value = (_scanResults.value.filter { it.address != id } + seen)
                .sortedByDescending { it.rssi }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (didConnectPeripheral !== peripheral) return
            _connectionState.value = ConnectionState.INITIALIZING
            // All services: the adapter may need to re-route by what is there.
            didConnectPeripheral.discoverServices(null)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            if (didFailToConnectPeripheral !== peripheral) return
            DiagnosticsLogger.note("Connect failed: ${error?.localizedDescription}")
            disconnect()
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            if (didDisconnectPeripheral !== peripheral) return
            DiagnosticsLogger.note("Disconnected: ${error?.localizedDescription ?: "by request"}")
            disconnect()
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val services = peripheral.services.orEmpty().map { it as CBService }
            var profile = adapter.bleProfile()
            if (services.none { it.UUID.toUuid() == profile.serviceUuid }) {
                val discovered = services.mapTo(HashSet()) { it.UUID.toUuid() }
                Log.w(TAG, "Service ${profile.serviceUuid} not on wheel; discovered=$discovered")
                if (adapter.pickAdapterByDiscoveredServices(discovered, currentName)) {
                    profile = adapter.bleProfile()
                    _connectedBrand.value = adapter.brand
                }
            }
            val notifyService = services.firstOrNull { it.UUID.toUuid() == profile.serviceUuid }
            val writeService = services.firstOrNull { it.UUID.toUuid() == profile.effectiveWriteServiceUuid }
            if (notifyService == null || writeService == null) {
                DiagnosticsLogger.note("Connect failed: no known wheel service on this device")
                disconnect()
                return
            }
            peripheral.discoverCharacteristics(null, notifyService)
            if (writeService !== notifyService) peripheral.discoverCharacteristics(null, writeService)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val profile = adapter.bleProfile()
            for (ch in didDiscoverCharacteristicsForService.characteristics.orEmpty()) {
                val c = ch as CBCharacteristic
                val u = c.UUID.toUuid()
                if (u == profile.notifyCharacteristic && notifyChar == null) {
                    notifyChar = c
                    peripheral.setNotifyValue(true, c)
                    // Some stacks never confirm the subscription; go anyway, as Android does.
                    scope.launch {
                        delay(CCCD_TIMEOUT_MS)
                        if (_connectionState.value == ConnectionState.INITIALIZING && writeChar != null) {
                            DiagnosticsLogger.note("Notification subscribe never confirmed - connecting anyway")
                            markConnected()
                        }
                    }
                }
                if (u == profile.writeCharacteristic && writeChar == null) {
                    writeChar = c
                    writeType = BleProfile.writeTypeFor(profile.writeType, c.properties.toGattProperties())
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (didUpdateNotificationStateForCharacteristic === notifyChar && writeChar != null) markConnected()
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val data = didUpdateValueForCharacteristic.value ?: return
            emitNotification(data.toByteArray())
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            pendingWriteAck?.trySend(error == null)
        }

        override fun peripheral(peripheral: CBPeripheral, didReadRSSI: NSNumber, error: NSError?) {
            if (error == null) _rssiDbm.value = didReadRSSI.intValue
        }
    }

    private companion object {
        const val TAG = "IosWheelLink"
        const val CCCD_TIMEOUT_MS = 4_000L
        const val WRITE_ACK_TIMEOUT_MS = 1_500L
        const val NO_RESPONSE_GAP_MS = 20L
    }
}

/** CoreBluetooth shortens Bluetooth SIG UUIDs to 4 hex digits; expand them first. */
private fun CBUUID.toUuid(): Uuid {
    val s = UUIDString
    val full = when (s.length) {
        4 -> "0000$s-0000-1000-8000-00805F9B34FB"
        8 -> "$s-0000-1000-8000-00805F9B34FB"
        else -> s
    }
    return Uuid.parse(full.lowercase())
}

private fun ULong.toGattProperties(): Int {
    var p = 0
    if (this and CBCharacteristicPropertyWrite != 0uL) p = p or GattCharacteristic.PROPERTY_WRITE
    if (this and CBCharacteristicPropertyWriteWithoutResponse != 0uL) p = p or GattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    return p
}

private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

/** What CoreBluetooth says of the radio. UNKNOWN until it has said (and while it resets). */
enum class BluetoothRadio { UNKNOWN, ON, OFF, NOT_ALLOWED, UNSUPPORTED }
