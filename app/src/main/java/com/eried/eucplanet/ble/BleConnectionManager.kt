package com.eried.eucplanet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import com.eried.eucplanet.ble.virtual.VirtualWheel
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    INITIALIZING,
    CONNECTED
}

@Singleton
class BleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelAdapter: WheelAdapter
) {
    companion object {
        private const val TAG = "BleConnection"

        // Client Characteristic Configuration Descriptor — same for every wheel family.
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _decodedResults = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 64)
    /** Stream of decoded results from the active wheel adapter. */
    val decodedResults: SharedFlow<DecodeResult> = _decodedResults.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var currentAddress: String? = null
    /** BLE advertised name from the most recent connect call, kept across reconnects. */
    private var currentName: String? = null
    private var shouldReconnect = true

    // Write serialization - only one BLE write at a time
    private val writeChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var writeReady = false

    // Active virtual wheel when in demo mode (address starts with "VIRTUAL:").
    // When non-null, writes are routed to the simulator instead of GATT and the
    // simulator's response bytes are fed back through the adapter pipeline as if
    // they had arrived as real BLE notifications. GATT handles stay null.
    @Volatile private var virtualWheel: VirtualWheel? = null

    init {
        scope.launch { processWriteQueue() }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null) {
        // Demo / simulator mode: VIRTUAL:<id> bypasses GATT and connects to a fake wheel.
        val virtualId = VirtualWheelRegistry.parsePseudoAddress(address)
        if (virtualId != null) {
            connectVirtual(virtualId)
            return
        }

        currentAddress = address
        // Hold on to the name so the auto-reconnect path keeps the same hint —
        // otherwise a P6 that briefly drops would come back as an unknown wheel.
        currentName = name ?: currentName
        shouldReconnect = true
        _connectionState.value = ConnectionState.CONNECTING

        // Adapter pre-selects model from the BLE name; needed for the InMotion
        // P6 because its legacy carType query returns zeros and we'd otherwise
        // never identify it before sending V14-shaped queries the wheel ignores.
        // If the adapter returned a ModelName from the name alone, surface it
        // immediately so the speed-limit slider cap (and other model-keyed UI
        // bits) reflect the wheel's real ceiling instead of the V14 fallback.
        wheelAdapter.notifyConnectingTo(currentName)?.let {
            _decodedResults.tryEmit(it)
        }

        val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Skip GATT entirely and run a simulated wheel. The fake produces the same
     * raw-byte responses a real wheel would emit, so the parser/adapter/repository
     * pipeline runs unchanged. Disconnect by calling [disconnect] as usual.
     */
    private fun connectVirtual(id: String) {
        val wheel = VirtualWheelRegistry.create(id) ?: run {
            Log.e(TAG, "Unknown virtual wheel id: $id")
            return
        }
        Log.i(TAG, "Connecting to virtual wheel: ${wheel.displayName}")
        wheel.reset()
        virtualWheel = wheel
        currentAddress = VirtualWheelRegistry.pseudoAddress(id)
        shouldReconnect = false
        _connectionState.value = ConnectionState.CONNECTING
        scope.launch {
            // Brief delays so the UI's connection-state animations actually animate.
            delay(150)
            _connectionState.value = ConnectionState.INITIALIZING
            for (resp in wheel.onConnect()) emitVirtualResponse(resp)
            delay(150)
            // Mark CONNECTED last so writeReady gates open AFTER on-connect responses
            // have already filtered through the adapter — same ordering a real wheel
            // gets, where notifications start landing once the GATT subscription is up.
            writeReady = true
            _connectionState.value = ConnectionState.CONNECTED
        }
    }

    private fun emitVirtualResponse(rawBytes: ByteArray) {
        for (result in wheelAdapter.onRawNotification(rawBytes)) {
            _decodedResults.tryEmit(result)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        currentAddress = null
        currentName = null
        rxCharacteristic = null
        writeReady = false

        // Virtual wheel: just drop the reference; no GATT to tear down.
        if (virtualWheel != null) {
            wheelAdapter.onDisconnect()
            virtualWheel = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val g = gatt
        if (g == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        // Request clean GATT teardown; close() runs in the STATE_DISCONNECTED callback
        // so the wheel's BLE stack receives the disconnect before we drop the handle.
        try {
            g.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "gatt.disconnect() threw", e)
        }
        // Safety net: if callback doesn't fire, force-close after a short delay.
        scope.launch {
            delay(1000)
            val still = gatt
            if (still != null) {
                Log.w(TAG, "Forcing GATT close after timeout")
                try { still.close() } catch (_: Exception) {}
                gatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun writeCommand(data: ByteArray) {
        Log.d(TAG, "Queuing write: ${data.joinToString(" ") { "%02x".format(it) }}")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.tx(data)
        writeChannel.trySend(data)
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWriteQueue() {
        for (data in writeChannel) {
            // Virtual mode: hand the write to the simulator and feed any responses
            // back through the adapter pipeline. No GATT, no writeReady gating.
            val virtual = virtualWheel
            if (virtual != null) {
                for (resp in virtual.onWrite(data)) emitVirtualResponse(resp)
                continue
            }
            if (!writeReady || rxCharacteristic == null || gatt == null) {
                Log.w(TAG, "Write skipped: ready=$writeReady rx=${rxCharacteristic != null} gatt=${gatt != null}")
                continue
            }

            writeReady = false
            val characteristic = rxCharacteristic ?: continue
            val g = gatt ?: continue

            // Use WRITE_TYPE_DEFAULT (write with response) for reliability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = g.writeCharacteristic(
                    characteristic, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                Log.d(TAG, "writeCharacteristic result: $result (${data.size} bytes)")
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val result = g.writeCharacteristic(characteristic)
                Log.d(TAG, "writeCharacteristic result: $result (${data.size} bytes)")
            }

            // Wait for write callback or timeout
            delay(20)
            if (!writeReady) {
                delay(180) // longer wait for write-with-response
                writeReady = true // assume success after timeout
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.INITIALIZING
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT (status=$status, shouldReconnect=$shouldReconnect)")
                    rxCharacteristic = null
                    writeReady = false
                    // Reset adapter framing state for the next connection
                    wheelAdapter.onDisconnect()
                    // Close the GATT here so the underlying connection is fully torn down
                    try { gatt.close() } catch (_: Exception) {}
                    if (this@BleConnectionManager.gatt === gatt) {
                        this@BleConnectionManager.gatt = null
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED

                    if (shouldReconnect && status != 0) {
                        scope.launch {
                            delay(2000)
                            currentAddress?.let { connect(it) }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val profile = wheelAdapter.bleProfile()
            val service = gatt.getService(profile.serviceUuid)
            if (service == null) {
                Log.e(TAG, "Adapter service ${profile.serviceUuid} not found on this wheel")
                return
            }

            rxCharacteristic = service.getCharacteristic(profile.writeCharacteristic)
            val txCharacteristic = service.getCharacteristic(profile.notifyCharacteristic)

            if (rxCharacteristic == null || txCharacteristic == null) {
                Log.e(TAG, "Adapter characteristics not found on service ${profile.serviceUuid}")
                return
            }

            // Enable notifications on TX
            gatt.setCharacteristicNotification(txCharacteristic, true)
            val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }

            writeReady = true
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Service ${profile.serviceUuid} ready (adapter=${wheelAdapter.familyId})")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite status=$status")
            writeReady = true
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            processIncomingData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingData(value)
        }
    }

    /**
     * Forward each BLE notification to the active wheel adapter and emit any
     * DecodeResults it produces. Framing (reassembly, parsing) lives in the
     * adapter — each protocol family has its own.
     */
    private fun processIncomingData(data: ByteArray) {
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.rx(data)
        for (result in wheelAdapter.onRawNotification(data)) {
            _decodedResults.tryEmit(result)
        }
    }
}
