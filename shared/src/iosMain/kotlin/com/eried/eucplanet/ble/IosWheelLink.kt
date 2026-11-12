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
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
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
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID
import platform.UIKit.UIApplicationWillEnterForegroundNotification
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
 * A wheel that drops is hunted as Android hunts it ([WheelReconnectHunt],
 * the shared rules): a scan for it in bounded windows with growing pauses,
 * plus a pending CoreBluetooth connect during each window, which iOS keeps
 * waiting in the background where a scan with no service filter finds
 * nothing. A disconnect the rider asks for, a virtual wheel or the scan
 * screen stops it. CoreBluetooth has no low-power scan, so a long hunt keeps
 * its scan. The link's RSSI is read once a second while connected.
 * Android's status-133 retries have no CoreBluetooth counterpart.
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
    private var rssiJob: Job? = null
    // Services whose characteristics have not been reported yet (1, or 2 for InMotion V1).
    private var servicesAwaitingCharacteristics = 0

    /** The wheel a hunt is scanning for, null when none is. */
    private var huntTarget: String? = null

    private val hunt = WheelReconnectHunt(
        radio = object : HuntRadio {
            override val adapterOn: Boolean get() = _bluetoothOn.value
            override var state: ConnectionState
                get() = _connectionState.value
                set(value) { _connectionState.value = value }

            override fun startHuntScan(address: String, lowPower: Boolean): Boolean {
                huntTarget = address
                Log.i(TAG, "Scanning to reconnect to $address" + if (lowPower) " (long hunt)" else "")
                central.scanForPeripheralsWithServices(null, null)
                // Also leave a connect waiting: iOS completes it when the wheel
                // comes back, in the background too, where the scan sees nothing.
                knownPeripheral(address)?.let { p ->
                    peripheral = p
                    p.delegate = peripheralDelegate
                    central.connectPeripheral(p, null)
                }
                return true
            }

            override fun stopHuntScan() {
                huntTarget = null
                if (!wantScan) central.stopScan()
                // The waiting connect goes with the window, as Android's scan does.
                val state = _connectionState.value
                if (state == ConnectionState.SCANNING || state == ConnectionState.DISCONNECTED) {
                    peripheral?.let { cancelLink(it) }
                    peripheral = null
                }
            }

            override fun connect(address: String, name: String?) = this@IosWheelLink.connect(address, name, isAuto = true)
        },
        clock = { System.currentTimeMillis() },
        timer = { delayMs, block -> scope.launch { delay(delayMs); block() } },
    )

    init {
        // As Android's AppForeground: opening the app restarts a slow hunt at full speed.
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> hunt.foreground() }
    }

    private fun knownPeripheral(address: String): CBPeripheral? = peripherals[address]
        ?: runCatching { NSUUID(uUIDString = address) }.getOrNull()?.let { id ->
            central.retrievePeripheralsWithIdentifiers(listOf(id)).firstOrNull() as? CBPeripheral
        }

    fun startScan() {
        wantScan = true
        _scanResults.value = emptyList()
        loggedSightings.clear()
        if (_bluetoothOn.value) {
            central.scanForPeripheralsWithServices(null, null)
            listSystemConnectedWheels()
        }
    }

    /** Ids already logged this scan, so the log names each device once. */
    private val loggedSightings = HashSet<String>()

    /**
     * A wheel the iPhone already holds (paired in Settings, or still linked
     * from another app) stops advertising, so the scan never sees it; Android
     * lists bonded wheels the same way. Ask iOS for every connected device
     * with a wheel's service and list it like a scan result.
     */
    private fun listSystemConnectedWheels() {
        val services = WHEEL_SERVICES.map { CBUUID.UUIDWithString(it) }
        val held = central.retrieveConnectedPeripheralsWithServices(services).map { it as CBPeripheral }
        for (p in held) {
            val id = p.identifier.UUIDString
            val name = p.name ?: continue
            peripherals[id] = p
            Log.i(TAG, "Scan: $name ($id) is already connected to the iPhone")
            if (_scanResults.value.none { it.address == id }) {
                _scanResults.value = _scanResults.value + ScannedWheel(id, name, HELD_RSSI)
            }
        }
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
        val virtualId = VirtualWheelRegistry.parsePseudoAddress(address)
        // While the rider is on the scan screen every automatic connect is held.
        if (!hunt.connectRequested(address, name, isAuto, virtual = virtualId != null)) {
            Log.i(TAG, "Auto-connect to $address held: scan screen is open")
            return
        }
        teardown()
        // Kept across reconnects, so a wheel that drops comes back under the same name.
        currentName = hunt.targetName
        _connectedAddress.value = address
        virtualId?.let { connectVirtual(it); return }

        // As Android: with Bluetooth off, remember the wheel and let Bluetooth
        // coming on start the hunt (connecting now would only fail).
        if (!_bluetoothOn.value) {
            Log.i(TAG, "connect($address) deferred: Bluetooth is off")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val p = knownPeripheral(address)
        if (p == null) {
            Log.e(TAG, "Unknown peripheral $address")
            _connectedAddress.value = null
            return
        }
        stopScan()
        DiagnosticsLogger.note("Connect requested: name=${currentName ?: "(unknown)"} addr=$address")
        adapter.notifyConnectingTo(currentName)?.let { _decodedResults.tryEmit(it) }
        _connectedBrand.value = adapter.brand
        _connectedDeviceName.value = currentName
        _connectionState.value = ConnectionState.CONNECTING
        peripheral = p
        p.delegate = peripheralDelegate
        central.connectPeripheral(p, null)
    }

    /** The rider's disconnect: forget the wheel, so it is not hunted. */
    override fun disconnect() {
        hunt.disconnectRequested()
        teardown()
        _connectedAddress.value = null
        _connectedDeviceName.value = null
        _connectedBrand.value = null
        currentName = null
    }

    /** Drops the link and everything about it, keeping the wheel to come back to. */
    private fun teardown() {
        rssiJob?.cancel()
        rssiJob = null
        virtualTickJob?.cancel()
        virtualTickJob = null
        virtualWheel = null
        writerJob?.cancel()
        writerJob = null
        writeQueue.clear()
        peripheral?.let { cancelLink(it) }
        peripheral = null
        writeChar = null
        notifyChar = null
        if (_connectionState.value != ConnectionState.DISCONNECTED) adapter.onDisconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedSinceMs.value = 0L
        _rssiDbm.value = null
    }

    override fun writeCommand(data: ByteArray, kind: BleWriteQueue.Kind) {
        if (data.isEmpty()) return
        DiagnosticsLogger.tx(data)
        // The queue is not thread safe and the writer takes from it on the main
        // thread, while the wheel's polling calls in from Dispatchers.IO; Android
        // guards it with a lock. On the main thread already, this runs inline.
        scope.launch(Dispatchers.Main.immediate) {
            if (!writeQueue.offer(kind, data)) {
                DiagnosticsLogger.note("Write DROPPED (${data.size}B) - the command queue is full")
            }
            writeSignal.trySend(Unit)
        }
    }

    override fun suppressAutoConnect() {
        stopScan()
        // The scan screen runs its own scan: no hunt beside it, and a wheel the
        // app connected by itself is let go. One the rider picked stays.
        if (hunt.suppress()) {
            Log.i(TAG, "Scan opened while auto-connected/connecting; dropping it")
            disconnect()
        }
    }

    override fun resumeAutoConnect(reconnectAddress: String?, reconnectName: String?) {
        hunt.resume()
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
        Log.i(TAG, "Connected: ${currentName ?: "(none)"} as ${adapter.familyDisplayName}")
        rxCount = 0
        _connectedSinceMs.value = System.currentTimeMillis()
        DiagnosticsLogger.note("Connected: name=${currentName ?: "(none)"} adapter=${adapter.familyDisplayName}")
        writerJob = scope.launch { drainWrites() }
        // The BT signal metric and proximity lock read it, as on Android. A
        // virtual wheel has no radio, so it stays unknown.
        rssiJob = scope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                delay(WheelReconnect.RSSI_POLL_MS)
                if (_connectionState.value == ConnectionState.CONNECTED) peripheral?.readRSSI()
            }
        }
    }

    /** Frames since the link came up; the first few are logged for the field log. */
    private var rxCount = 0

    private fun emitNotification(bytes: ByteArray) {
        if (rxCount++ < 3) Log.i(TAG, "Wheel data: ${bytes.size} bytes")
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
            // One frame can be several writes on a profile with a write size
            // limit (InMotion V1's 20 bytes). All of them go out here, each
            // acked before the next, as on Android; a whole V1 frame in one
            // write would be a GATT long write, which drops the V1's link.
            for (piece in adapter.bleProfile().writePieces(entry.data)) {
                // A piece that never went leaves half a frame; the rest is dropped.
                if (!writePiece(p, c, piece)) break
            }
        }
    }

    /**
     * One write, then its ack, as Android's writePiece. CoreBluetooth never
     * refuses a write on the spot, so every write counts as accepted and is
     * never sent twice (Android retries only a write the stack refused): a
     * late or refused ack is logged and the queue moves on. False when the
     * link is gone, so the caller drops the rest of the frame.
     */
    private suspend fun writePiece(p: CBPeripheral, c: CBCharacteristic, piece: ByteArray): Boolean {
        if (p.state != CBPeripheralStateConnected) return false
        if (writeType == GattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // CoreBluetooth drops a no-response write while its buffer is full;
            // wait for room, up to the 200 ms Android gives its local callback.
            withTimeoutOrNull(WRITE_ACK_NO_RESPONSE_MS) {
                while (!p.canSendWriteWithoutResponse) delay(5)
            }
            p.writeValue(piece.toNSData(), c, CBCharacteristicWriteWithoutResponse)
            // No callback for these; a short gap keeps HM-10 modules paced.
            delay(NO_RESPONSE_GAP_MS)
            return true
        }
        val ack = Channel<Boolean>(1)
        pendingWriteAck = ack
        p.writeValue(piece.toNSData(), c, CBCharacteristicWriteWithResponse)
        val acked = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.receive() }
        pendingWriteAck = null
        if (acked == null) DiagnosticsLogger.note("Write ACK timed out after $WRITE_ACK_TIMEOUT_MS ms (${piece.size}B)")
        return true
    }

    /**
     * Whether a callback is about the wheel this link is for. By identifier,
     * not by reference: Kotlin/Native can hand the same CBPeripheral back as
     * a different reference, and the connect was then ignored, so iOS held
     * the wheel while the app kept searching.
     */
    private fun isOurs(p: CBPeripheral): Boolean =
        peripheral?.identifier?.UUIDString == p.identifier.UUIDString

    /** Links this app cancelled itself, whose "disconnected" report is not news. */
    private val cancelledLinks = mutableMapOf<String, Int>()

    private fun cancelLink(p: CBPeripheral) {
        val id = p.identifier.UUIDString
        cancelledLinks[id] = (cancelledLinks[id] ?: 0) + 1
        central.cancelPeripheralConnection(p)
    }

    /**
     * The report for a link this app cancelled. Matching by id, the cancel of
     * the hunt's waiting connect would otherwise read as the fresh connect to
     * the same wheel ending, and tear it down.
     */
    private fun consumeCancelled(p: CBPeripheral, error: NSError?): Boolean {
        if (error != null) return false
        val id = p.identifier.UUIDString
        val n = cancelledLinks[id] ?: return false
        if (n <= 1) cancelledLinks.remove(id) else cancelledLinks[id] = n - 1
        return true
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
            if (_bluetoothOn.value && wantScan) {
                central.scanForPeripheralsWithServices(null, null)
                listSystemConnectedWheels()
            }
            if (_bluetoothOn.value) {
                hunt.bluetoothOn()
            } else {
                hunt.bluetoothOff()
                // As Android: the link is gone with the radio; keep the wheel to come back to.
                if (virtualWheel == null && _connectionState.value != ConnectionState.DISCONNECTED) {
                    Log.i(TAG, "Bluetooth turned off; forcing disconnect")
                    teardown()
                }
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            if (id == huntTarget) {
                peripherals[id] = didDiscoverPeripheral
                Log.i(TAG, "Reconnect scan found $id; connecting")
                hunt.seen(id)
                if (!wantScan) return
            }
            // The advertised name when iOS has not cached one yet, as Android
            // reads the scan record's name.
            val name = didDiscoverPeripheral.name
                ?: advertisementData[CBAdvertisementDataLocalNameKey] as? String
                ?: return
            if (loggedSightings.add(id)) Log.i(TAG, "Scan: $name ($id) rssi=${RSSI.intValue}")
            peripherals[id] = didDiscoverPeripheral
            val seen = ScannedWheel(id, name, RSSI.intValue)
            _scanResults.value = (_scanResults.value.filter { it.address != id } + seen)
                .sortedByDescending { it.rssi }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            Log.i(TAG, "Link up: ${didConnectPeripheral.name} (${didConnectPeripheral.identifier.UUIDString}), state=${_connectionState.value}")
            if (!isOurs(didConnectPeripheral)) {
                Log.w(TAG, "Link up on a peripheral this connect is not for; ignored")
                return
            }
            val state = _connectionState.value
            if (state == ConnectionState.SCANNING || state == ConnectionState.DISCONNECTED) {
                // The connect left waiting by the hunt landed: it is a reconnect.
                Log.i(TAG, "Wheel came back; reconnected")
                _connectionState.value = ConnectionState.CONNECTING
                hunt.reconnected()
                adapter.notifyConnectingTo(currentName)?.let { _decodedResults.tryEmit(it) }
                _connectedBrand.value = adapter.brand
                _connectedDeviceName.value = currentName
            }
            _connectionState.value = ConnectionState.INITIALIZING
            // All services: the adapter may need to re-route by what is there.
            didConnectPeripheral.discoverServices(null)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            Log.w(TAG, "Link failed: ${error?.localizedDescription}")
            if (consumeCancelled(didFailToConnectPeripheral, error)) return
            if (!isOurs(didFailToConnectPeripheral)) return
            DiagnosticsLogger.note("Connect failed: ${error?.localizedDescription}")
            teardown()
            hunt.dropped(unexpected = true)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            Log.i(TAG, "Link down: ${error?.localizedDescription ?: "by request"}")
            if (consumeCancelled(didDisconnectPeripheral, error)) return
            if (!isOurs(didDisconnectPeripheral)) return
            DiagnosticsLogger.note("Disconnected: ${error?.localizedDescription ?: "by request"}")
            teardown()
            // No error: the app asked for it. An error: the wheel went away.
            hunt.dropped(unexpected = error != null)
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val services = peripheral.services.orEmpty().map { it as CBService }
            Log.i(TAG, "Services: ${services.map { it.UUID.UUIDString }}" + (didDiscoverServices?.let { " error=${it.localizedDescription}" } ?: ""))
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
            servicesAwaitingCharacteristics = if (writeService.UUID != notifyService.UUID) 2 else 1
            peripheral.discoverCharacteristics(null, notifyService)
            if (writeService.UUID != notifyService.UUID) peripheral.discoverCharacteristics(null, writeService)
        }

        /** As Android's failConnectAndTeardown: say why, then let the wheel go. */
        private fun failConnect(reason: String) {
            Log.e(TAG, "Connect failed: $reason")
            DiagnosticsLogger.note("Connect failed: name=${currentName ?: "(unknown)"} reason=$reason")
            disconnect()
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val profile = adapter.bleProfile()
            if (error != null) {
                failConnect("Characteristic discovery failed (${error.localizedDescription})")
                return
            }
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
                    val props = c.properties.toGattProperties()
                    writeType = BleProfile.writeTypeFor(profile.writeType, props)
                    DiagnosticsLogger.note(BleProfile.writeTypeNote(props, writeType, switched = writeType != profile.writeType))
                }
            }
            // Both services reported and a characteristic is still missing: the
            // link would sit initializing forever, so give up as Android does.
            servicesAwaitingCharacteristics--
            if (servicesAwaitingCharacteristics <= 0 && (writeChar == null || notifyChar == null)) {
                failConnect(
                    "Adapter characteristics not found (write ${profile.writeCharacteristic} on " +
                        "service ${profile.effectiveWriteServiceUuid}, notify ${profile.notifyCharacteristic} " +
                        "on service ${profile.serviceUuid})"
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (didUpdateNotificationStateForCharacteristic.UUID != notifyChar?.UUID) return
            // Whether we are subscribed decides how to read a silent wheel, as on Android.
            DiagnosticsLogger.note(
                if (error == null) "Subscribed to wheel notifications"
                else "Notification subscribe FAILED (${error.localizedDescription}) - replies cannot reach us"
            )
            Log.i(TAG, "Subscribe: " + (error?.localizedDescription ?: "ok"))
            if (writeChar != null) markConnected()
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
            // A refused write never reached the firmware; without this line it
            // looks the same as a delivered one in the log, as on Android.
            if (error != null) DiagnosticsLogger.note("Write REFUSED by the wheel (${error.localizedDescription})")
            pendingWriteAck?.trySend(error == null)
        }

        override fun peripheral(peripheral: CBPeripheral, didReadRSSI: NSNumber, error: NSError?) {
            // A read that lands after the link dropped would leave a stale value up.
            if (error == null && _connectionState.value == ConnectionState.CONNECTED) _rssiDbm.value = didReadRSSI.intValue
        }
    }

    private companion object {
        const val TAG = "IosWheelLink"
        const val CCCD_TIMEOUT_MS = 4_000L

        /** The wheels' services: HM-10 (KingSong, Begode, Veteran, InMotion V1 notify), InMotion V1 write, Nordic UART. */
        val WHEEL_SERVICES = listOf(
            "FFE0",
            "FFE5",
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        )

        /** A wheel already linked has no scan signal; list it as strong, it is right here. */
        const val HELD_RSSI = -40
        const val WRITE_ACK_TIMEOUT_MS = 1_500L
        const val NO_RESPONSE_GAP_MS = 20L
        // Android's WRITE_ACK_NO_RESPONSE_MS.
        const val WRITE_ACK_NO_RESPONSE_MS = 200L
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
