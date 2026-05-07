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
    private var shouldReconnect = true

    // Write serialization - only one BLE write at a time
    private val writeChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var writeReady = false

    init {
        scope.launch { processWriteQueue() }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        currentAddress = address
        shouldReconnect = true
        _connectionState.value = ConnectionState.CONNECTING

        val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        currentAddress = null
        rxCharacteristic = null
        writeReady = false
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
        writeChannel.trySend(data)
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWriteQueue() {
        for (data in writeChannel) {
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
        for (result in wheelAdapter.onRawNotification(data)) {
            _decodedResults.tryEmit(result)
        }
    }
}
