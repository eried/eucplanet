@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.ble.BluetoothRestore
import com.eried.eucplanet.ble.cbUuid
import com.eried.eucplanet.ble.toCBUUID
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.uuid.Uuid

/**
 * The CoreBluetooth side of [EngoGlasses], Android's EngoAdapter on the
 * iPhone: a central of its own, so the wheel, the radar and the glasses never
 * share a connection.
 *
 * The same steps as Android, one request at a time: connect, find the
 * ActiveLook service and the Device Information service, read the hardware
 * version (it says whether the display is colour), turn on the Control
 * notifications (flow control) and the TX ones, then CONNECTED. Frames are
 * written one at a time, each waiting for the glasses' answer, and wait while
 * the glasses ask for a pause.
 *
 * iOS picks the MTU itself (Android asks for 512); a frame longer than one
 * packet goes as a long write, which CoreBluetooth splits.
 *
 * The address is iOS's id for the glasses (Android's is the MAC address), so
 * a pair remembered on one phone is paired again on the other.
 */
class IosEngoGlasses : EngoGlasses {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(EngoGlasses.State.DISCONNECTED)
    override val state: StateFlow<EngoGlasses.State> = _state.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _found = MutableStateFlow<List<EngoFound>>(emptyList())
    override val found: StateFlow<List<EngoFound>> = _found.asStateFlow()

    private val _colorCapable = MutableStateFlow(false)
    override val colorCapable: StateFlow<Boolean> = _colorCapable.asStateFlow()

    private var peripheral: CBPeripheral? = null
    private var rx: CBCharacteristic? = null

    /** Looking for glasses iOS has forgotten, by their id. */
    private var lookingFor: String? = null

    /** Services whose characteristics have not been reported yet. */
    private var servicesAwaitingCharacteristics = 0

    /** The setup requests still to send after connecting, in order. */
    private val setup = ArrayDeque<() -> Unit>()

    // Write pump: one write in flight at a time.
    private val queue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var flowPaused = false

    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private val central = CBCentralManager(centralDelegate, null, BluetoothRestore.options(BluetoothRestore.ENGO))

    private fun bluetoothOn() = central.state == CBManagerStatePoweredOn

    // The renderer calls in from Dispatchers.Default; CoreBluetooth and this
    // link's state live on the main thread, where the delegates run.
    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun startScan() = onMain {
        if (_scanning.value) return@onMain
        if (!bluetoothOn()) { Log.w(TAG, "bluetooth off"); return@onMain }
        _found.value = emptyList()
        _scanning.value = true
        Log.i(TAG, "scanning")
        // No service filter: the glasses are known by their name or the
        // ActiveLook maker id, as on Android, not by an advertised service.
        central.scanForPeripheralsWithServices(null, null)
    }

    override fun stopScan() = onMain {
        if (_scanning.value) {
            _scanning.value = false
            if (lookingFor == null) central.stopScan()
        }
    }

    override fun connect(address: String) = onMain {
        if (_state.value != EngoGlasses.State.DISCONNECTED) return@onMain
        if (!bluetoothOn()) { Log.w(TAG, "bluetooth off"); return@onMain }
        _state.value = EngoGlasses.State.CONNECTING
        Log.i(TAG, "connecting to $address")
        val p = known(address)
        if (p == null) {
            // Not known to iOS any more: find it by its id, then connect.
            lookingFor = address
            central.scanForPeripheralsWithServices(null, null)
            return@onMain
        }
        open(p)
    }

    override fun disconnect() = onMain { drop() }

    override fun send(frames: List<ByteArray>) = onMain {
        if (_state.value != EngoGlasses.State.CONNECTED) return@onMain
        queue.addAll(frames)
        pump()
    }

    private fun open(p: CBPeripheral) {
        peripheral = p
        p.delegate = peripheralDelegate
        if (p.state == CBPeripheralStateConnected) {
            centralDelegate.centralManager(central, didConnectPeripheral = p)
        } else {
            central.connectPeripheral(p, null)
        }
    }

    private fun known(address: String): CBPeripheral? {
        val id = runCatching { NSUUID(uUIDString = address) }.getOrNull() ?: return null
        return central.retrievePeripheralsWithIdentifiers(listOf(id)).firstOrNull() as? CBPeripheral
    }

    private fun drop() {
        if (lookingFor != null && !_scanning.value) central.stopScan()
        lookingFor = null
        peripheral?.let { central.cancelPeripheralConnection(it) }
        peripheral = null
        rx = null
        setup.clear()
        queue.clear()
        servicesAwaitingCharacteristics = 0
        writeInFlight = false
        flowPaused = false
        _colorCapable.value = false
        _state.value = EngoGlasses.State.DISCONNECTED
    }

    private fun isOurs(p: CBPeripheral) = peripheral?.identifier?.UUIDString == p.identifier.UUIDString

    /** Send the next setup request; with none left the glasses are ready. */
    private fun nextSetup() {
        val step = setup.removeFirstOrNull()
        if (step != null) { step(); return }
        if (_state.value == EngoGlasses.State.CONNECTING) {
            _state.value = EngoGlasses.State.CONNECTED
            Log.i(TAG, "connected, colour=${_colorCapable.value}")
        }
    }

    private fun pump() {
        val p = peripheral ?: return
        val ch = rx ?: return
        if (flowPaused || writeInFlight) return
        val frame = queue.removeFirstOrNull() ?: return
        writeInFlight = true
        p.writeValue(frame.toNSData(), ch, CBCharacteristicWriteWithResponse)
    }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (!bluetoothOn()) {
                _scanning.value = false
                if (peripheral != null || lookingFor != null || _state.value != EngoGlasses.State.DISCONNECTED) {
                    Log.i(TAG, "Bluetooth turned off")
                    drop()
                }
            }
            // Back on: the renderer's search loop links again by itself.
        }

        /** iOS relaunched the app for this central; the renderer connects again by id. */
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            Log.i(TAG, "Restored by iOS: " + BluetoothRestore.peripherals(willRestoreState).map { BluetoothRestore.describe(it) })
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            if (id == lookingFor) {
                lookingFor = null
                if (!_scanning.value) central.stopScan()
                open(didDiscoverPeripheral)
                return
            }
            if (!_scanning.value) return
            val name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?: didDiscoverPeripheral.name
            val maker = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray()
            // The maker id is the data's first two bytes, little-endian.
            val makerIds = if (maker != null && maker.size >= 2) {
                listOf((maker[0].toInt() and 0xFF) or ((maker[1].toInt() and 0xFF) shl 8))
            } else emptyList()
            if (!EngoLink.isGlasses(name, makerIds)) return
            val seen = EngoFound(id, name?.trim()?.ifEmpty { null } ?: id, RSSI.intValue)
            if (_found.value.none { it.address == id }) Log.i(TAG, "found ${seen.name}, makers $makerIds")
            _found.value = _found.value.filter { it.address != id } + seen
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            if (!isOurs(didConnectPeripheral)) return
            didConnectPeripheral.discoverServices(listOf(SERVICE.toCBUUID(), DEVICE_INFO.toCBUUID()))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            if (!isOurs(didFailToConnectPeripheral)) return
            Log.w(TAG, "link failed: ${error?.localizedDescription}")
            peripheral = null
            drop()
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            if (!isOurs(didDisconnectPeripheral)) return
            Log.i(TAG, "disconnected: ${error?.localizedDescription ?: "by request"}")
            peripheral = null
            drop()
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val services = peripheral.services.orEmpty().map { it as CBService }
            val activeLook = services.firstOrNull { it.UUID.cbUuid() == SERVICE }
            if (didDiscoverServices != null || activeLook == null) {
                Log.w(TAG, "ActiveLook service missing - not an ENGO?")
                drop()
                return
            }
            val info = services.firstOrNull { it.UUID.cbUuid() == DEVICE_INFO }
            servicesAwaitingCharacteristics = if (info != null) 2 else 1
            peripheral.discoverCharacteristics(listOf(RX.toCBUUID(), TX.toCBUUID(), CTRL.toCBUUID()), activeLook)
            info?.let { peripheral.discoverCharacteristics(listOf(HARDWARE_REV.toCBUUID()), it) }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val service = didDiscoverCharacteristicsForService
            val chars = service.characteristics.orEmpty().map { it as CBCharacteristic }
            fun char(uuid: Uuid) = chars.firstOrNull { it.UUID.cbUuid() == uuid }
            if (service.UUID.cbUuid() == SERVICE) {
                rx = char(RX)
                if (rx == null) { drop(); return }
                // After the hardware read, which the other service queues first.
                char(CTRL)?.let { c -> setup.addLast { peripheral.setNotifyValue(true, c) } }
                char(TX)?.let { c -> setup.addLast { peripheral.setNotifyValue(true, c) } }
            } else {
                char(HARDWARE_REV)?.let { c -> setup.addFirst { peripheral.readValueForCharacteristic(c) } }
            }
            if (--servicesAwaitingCharacteristics == 0) nextSetup()
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error != null) Log.w(TAG, "notify refused: ${error.localizedDescription}")
            nextSetup()
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val ch = didUpdateValueForCharacteristic
            val value = ch.value?.toByteArray()
            when (ch.UUID.cbUuid()) {
                HARDWARE_REV -> {
                    val text = value?.decodeToString()?.trim().orEmpty()
                    if (error == null) {
                        _colorCapable.value = EngoLink.isColor(text)
                        Log.i(TAG, "hardware \"$text\"")
                    }
                    nextSetup()
                }
                CTRL -> if (value != null && value.isNotEmpty()) {
                    when (value[0].toInt() and 0xFF) {
                        ActiveLookProtocol.FLOW_PAUSE -> flowPaused = true
                        ActiveLookProtocol.FLOW_RESUME -> { flowPaused = false; pump() }
                    }
                }
                else -> Unit
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error != null) Log.w(TAG, "write refused: ${error.localizedDescription}")
            writeInFlight = false
            pump()
        }
    }

    private companion object {
        const val TAG = "EngoGlasses"
        val SERVICE: Uuid = Uuid.parse(ActiveLookProtocol.SERVICE_UUID)
        val RX: Uuid = Uuid.parse(ActiveLookProtocol.CHAR_RX_UUID)
        val TX: Uuid = Uuid.parse(ActiveLookProtocol.CHAR_TX_UUID)
        val CTRL: Uuid = Uuid.parse(ActiveLookProtocol.CHAR_CTRL_UUID)

        /** Bluetooth's standard Device Information service and its hardware version. */
        val DEVICE_INFO: Uuid = Uuid.parse("0000180a-0000-1000-8000-00805f9b34fb")
        val HARDWARE_REV: Uuid = Uuid.parse("00002a27-0000-1000-8000-00805f9b34fb")
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
