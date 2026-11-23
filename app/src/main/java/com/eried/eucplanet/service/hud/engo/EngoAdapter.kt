package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.util.canConnectBluetooth
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE output client for ENGO 2 / 3 (ActiveLook) glasses. A scan lists the
 * glasses in range for the rider to pair; from then on it connects to that
 * pair by address, negotiates MTU, then writes ActiveLook command frames
 * to the RX characteristic while honouring the Control-characteristic flow
 * control (pause on 0x02, resume on 0x01).
 *
 * Structurally mirrors [com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager]
 * (the app's other hand-rolled BLE peripheral), but this one is write-oriented:
 * we push a screen's worth of frames, the glasses just display them.
 *
 * Finish-on-device: the exact advertised name prefix, MTU / flow-control timing,
 * per-frame chunking above the MTU, and colour-capability detection are tuning
 * points confirmed on a real unit. The frames themselves come from the
 * fully-tested [ActiveLookProtocol] + [EngoLayout].
 */
class EngoAdapter(
    private val context: Context,
) : EngoGlasses {
    companion object {
        private const val TAG = "EngoAdapter"
        private const val DEFAULT_NAME_PREFIX = "ENGO" // confirm on device
        private const val DESIRED_MTU = 512
        private val SERVICE = UUID.fromString(ActiveLookProtocol.SERVICE_UUID)
        private val CHAR_RX = UUID.fromString(ActiveLookProtocol.CHAR_RX_UUID)
        private val CHAR_TX = UUID.fromString(ActiveLookProtocol.CHAR_TX_UUID)
        // Bluetooth's standard Device Information service; its hardware
        // version string names the display module (see [EngoLink.isColor]).
        private val DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val HARDWARE_REV = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        private val CHAR_CTRL = UUID.fromString(ActiveLookProtocol.CHAR_CTRL_UUID)
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(EngoGlasses.State.DISCONNECTED)
    override val state: StateFlow<EngoGlasses.State> = _state.asStateFlow()

    /** Whether a pairing scan is running, and the glasses it has found. */
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _found = MutableStateFlow<List<EngoFound>>(emptyList())
    override val found: StateFlow<List<EngoFound>> = _found.asStateFlow()

    /** True once the connected glasses say their display is colour: their
     *  hardware version string is read right after connecting. False until
     *  then and for a grey pair, so those only ever get grey commands. */
    private val _colorCapable = MutableStateFlow(false)
    override val colorCapable: StateFlow<Boolean> = _colorCapable.asStateFlow()

    private val bluetooth by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null

    // Write pump: one BLE write may be in flight at a time. Frames queue here and
    // drain on each onCharacteristicWrite, pausing while flow control says so.
    private val queue = ArrayDeque<ByteArray>()
    private val queueLock = Any()
    @Volatile private var writeInFlight = false
    @Volatile private var flowPaused = false

    /** Look for glasses to pair. Results arrive in [found]. */
    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (_scanning.value) return
        val adapter = bluetooth ?: run { Log.w(TAG, "no BluetoothAdapter"); return }
        if (!adapter.isEnabled) { Log.w(TAG, "bluetooth off"); return }
        _found.value = emptyList()
        _scanning.value = true
        DiagnosticsLogger.note("Engo: scanning")
        runCatching { adapter.bluetoothLeScanner?.startScan(scanCallback) }
            .onFailure { _scanning.value = false }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        runCatching { bluetooth?.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
    }

    /** Link to the paired glasses at [address]. No scan: the phone asks for
     *  that pair directly, which also works with the screen off. An attempt
     *  the phone gives up on ends as DISCONNECTED and is made again. */
    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        if (_state.value != EngoGlasses.State.DISCONNECTED) return
        val adapter = bluetooth ?: run { Log.w(TAG, "no BluetoothAdapter"); return }
        if (!adapter.isEnabled) { Log.w(TAG, "bluetooth off"); return }
        // Nearby devices taken away: stay disconnected instead of crashing.
        if (!context.canConnectBluetooth()) { Log.w(TAG, "Nearby devices permission missing"); return }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        _state.value = EngoGlasses.State.CONNECTING
        DiagnosticsLogger.note("Engo: connecting to $address")
        gatt = device.connectGatt(
            context, /* autoConnect = */ false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        rx = null
        synchronized(queueLock) { queue.clear() }
        writeInFlight = false
        flowPaused = false
        _colorCapable.value = false
        _state.value = EngoGlasses.State.DISCONNECTED
    }

    /** Enqueue a screen's frames; the pump writes them respecting flow control. */
    override fun send(frames: List<ByteArray>) {
        if (_state.value != EngoGlasses.State.CONNECTED) return
        synchronized(queueLock) { frames.forEach(queue::add) }
        pump()
    }

    // --- scanning ---
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName
            val makers = result.scanRecord?.manufacturerSpecificData
            val makerIds = if (makers == null) emptyList() else List(makers.size()) { makers.keyAt(it) }
            if (!EngoLink.isGlasses(name, makerIds, DEFAULT_NAME_PREFIX)) return
            val address = result.device?.address ?: return
            val seen = EngoFound(address, name?.trim()?.ifEmpty { null } ?: address, result.rssi)
            if (_found.value.none { it.address == address }) {
                DiagnosticsLogger.note("Engo: found ${seen.name}, makers $makerIds")
            }
            _found.value = _found.value.filter { it.address != address } + seen
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _scanning.value = false
        }
    }

    // --- gatt ---
    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                if (!g.requestMtu(DESIRED_MTU)) g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                DiagnosticsLogger.note("Engo: disconnected (status=$status)")
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(); return }
            val svc = g.getService(SERVICE) ?: run {
                Log.w(TAG, "ActiveLook service not found")
                DiagnosticsLogger.note("Engo: ActiveLook service missing - not an ENGO?")
                disconnect(); return
            }
            rx = svc.getCharacteristic(CHAR_RX)
            if (rx == null) { disconnect(); return }
            // One Bluetooth request at a time, each waiting for the last one's
            // answer: what display the glasses have, then Control (flow
            // control) and TX (responses). Sent together, all but the first
            // were refused.
            synchronized(setup) {
                setup.clear()
                g.getService(DEVICE_INFO)?.getCharacteristic(HARDWARE_REV)?.let { hw ->
                    setup += { g.readCharacteristic(hw) }
                }
                svc.getCharacteristic(CHAR_CTRL)?.let { c -> setup += { enableNotify(g, c) } }
                svc.getCharacteristic(CHAR_TX)?.let { c -> setup += { enableNotify(g, c) } }
            }
            nextSetup()
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            if (ch.uuid == HARDWARE_REV && status == BluetoothGatt.GATT_SUCCESS) hardware(legacyValue(ch))
            nextSetup()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            if (ch.uuid == HARDWARE_REV && status == BluetoothGatt.GATT_SUCCESS) hardware(value)
            nextSetup()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            nextSetup()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            writeInFlight = false
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotify(ch.uuid, legacyValue(ch))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotify(ch.uuid, value)
        }
    }

    /** The setup requests still to send after connecting, in order. */
    private val setup = ArrayDeque<() -> Boolean>()

    /** Send the next setup request; one the phone refuses is skipped. With
     *  none left the glasses are ready. */
    private fun nextSetup() {
        while (true) {
            val step = synchronized(setup) { if (setup.isEmpty()) null else setup.poll() }
            if (step == null) {
                if (_state.value == EngoGlasses.State.CONNECTING) {
                    _state.value = EngoGlasses.State.CONNECTED
                    DiagnosticsLogger.note("Engo: connected, colour=${_colorCapable.value}")
                }
                return
            }
            if (runCatching { step() }.getOrDefault(false)) return
        }
    }

    private fun hardware(value: ByteArray?) {
        val text = value?.toString(Charsets.US_ASCII)?.trim().orEmpty()
        _colorCapable.value = EngoLink.isColor(text)
        DiagnosticsLogger.note("Engo: hardware \"$text\"")
    }

    private fun handleNotify(uuid: UUID, value: ByteArray?) {
        if (uuid != CHAR_CTRL || value == null || value.isEmpty()) return
        when (value[0].toInt() and 0xFF) {
            ActiveLookProtocol.FLOW_PAUSE -> flowPaused = true
            ActiveLookProtocol.FLOW_RESUME -> { flowPaused = false; pump() }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyValue(ch: BluetoothGattCharacteristic): ByteArray? = ch.value

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    /** Drain the write queue, one frame at a time, while connected and not paused. */
    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return
        val ch = rx ?: return
        if (flowPaused || writeInFlight) return
        val frame = synchronized(queueLock) { if (queue.isEmpty()) null else queue.poll() } ?: return
        writeInFlight = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }
}
