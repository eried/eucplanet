package com.eried.eucplanet.service.hud.engo

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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE output client for ENGO 2 / 3 (ActiveLook) glasses. Scans by advertised
 * name prefix, connects, negotiates MTU, then writes ActiveLook command frames
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
@Singleton
class EngoAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "EngoAdapter"
        private const val DEFAULT_NAME_PREFIX = "ENGO" // confirm on device
        private const val DESIRED_MTU = 512
        private val SERVICE = UUID.fromString(ActiveLookProtocol.SERVICE_UUID)
        private val CHAR_RX = UUID.fromString(ActiveLookProtocol.CHAR_RX_UUID)
        private val CHAR_TX = UUID.fromString(ActiveLookProtocol.CHAR_TX_UUID)
        private val CHAR_CTRL = UUID.fromString(ActiveLookProtocol.CHAR_CTRL_UUID)
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once the connected model is known to be RG-colour (ENGO 3). Until a
     *  device confirms it via device-info, stays false (mono path). */
    private val _colorCapable = MutableStateFlow(false)
    val colorCapable: StateFlow<Boolean> = _colorCapable.asStateFlow()

    private val bluetooth by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var namePrefix = DEFAULT_NAME_PREFIX

    // Write pump: one BLE write may be in flight at a time. Frames queue here and
    // drain on each onCharacteristicWrite, pausing while flow control says so.
    private val queue = ArrayDeque<ByteArray>()
    private val queueLock = Any()
    @Volatile private var writeInFlight = false
    @Volatile private var flowPaused = false

    @SuppressLint("MissingPermission")
    fun connect(prefix: String = DEFAULT_NAME_PREFIX) {
        if (_state.value != State.DISCONNECTED) return
        val adapter = bluetooth ?: run { Log.w(TAG, "no BluetoothAdapter"); return }
        if (!adapter.isEnabled) { Log.w(TAG, "bluetooth off"); return }
        namePrefix = prefix
        _state.value = State.SCANNING
        DiagnosticsLogger.note("Engo: scanning for \"$prefix\"*")
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        rx = null
        synchronized(queueLock) { queue.clear() }
        writeInFlight = false
        flowPaused = false
        _colorCapable.value = false
        _state.value = State.DISCONNECTED
    }

    /** Enqueue a screen's frames; the pump writes them respecting flow control. */
    fun send(frames: List<ByteArray>) {
        if (_state.value != State.CONNECTED) return
        synchronized(queueLock) { frames.forEach(queue::add) }
        pump()
    }

    // --- scanning ---
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        runCatching { bluetooth?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith(namePrefix, ignoreCase = true)) return
            stopScan()
            _state.value = State.CONNECTING
            DiagnosticsLogger.note("Engo: connecting to $name")
            gatt = result.device.connectGatt(context, /* autoConnect = */ false, callback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _state.value = State.DISCONNECTED
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
            // Subscribe to Control (flow control) and TX (responses).
            svc.getCharacteristic(CHAR_CTRL)?.let { enableNotify(g, it) }
            svc.getCharacteristic(CHAR_TX)?.let { enableNotify(g, it) }
            if (rx == null) { disconnect(); return }
            _state.value = State.CONNECTED
            DiagnosticsLogger.note("Engo: connected")
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
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        ch.getDescriptor(CCCD)?.let { d ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
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
