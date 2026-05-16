package com.eried.eucplanet.ble.gps

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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT instance dedicated to an external BLE GPS box. Independent of
 * [com.eried.eucplanet.ble.BleConnectionManager] so the wheel and the GPS box
 * each hold their own connection without interfering.
 *
 * Responsibilities: open the connection, request a larger MTU + high
 * connection priority, discover services, subscribe to the Nordic UART TX
 * characteristic, write whatever post-connect init the adapter wants on the
 * RX characteristic (for RaceBox: MGA-INI-TIME + MGA-INI-POS to skip
 * cold-start GNSS search), and forward each TX notification to the adapter.
 */
@Singleton
class ExternalGpsConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExtGpsConn"
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Matches the official RaceBox app capture (btsnoop 2026-05-13). */
        private const val DESIRED_MTU = 247
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<ExternalGpsSample>(extraBufferCapacity = 16)
    val samples: SharedFlow<ExternalGpsSample> = _samples.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var activeAdapter: ExternalGpsAdapter? = null

    /**
     * Pending init writes the adapter wants to send on the RX char after the
     * TX notification subscription completes. We pop them one at a time and
     * wait for each write to ACK (onCharacteristicWrite) before sending the
     * next, because the BluetoothGatt API only allows one in-flight write
     * per connection.
     */
    private val pendingInitWrites = ArrayDeque<ByteArray>()
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun connect(address: String, adapter: ExternalGpsAdapter, initWrites: List<ByteArray>) {
        disconnect()
        activeAdapter = adapter
        pendingInitWrites.clear()
        pendingInitWrites.addAll(initWrites)
        val device: BluetoothDevice = try {
            bluetoothManager.adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad device address $address", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to ${adapter.source.displayName} @ $address (${initWrites.size} init writes queued)")
        gatt = device.connectGatt(context, /* autoConnect = */ false, callback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        activeAdapter = null
        rxCharacteristic = null
        pendingInitWrites.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected (status=$status), requesting MTU $DESIRED_MTU")
                    _connectionState.value = ConnectionState.INITIALIZING
                    // Bump connection priority so the streaming side doesn't
                    // get throttled while we're also driving the wheel
                    // connection. The standard "balanced" interval (~50 ms)
                    // would still work but trims GPS lock-time headroom on
                    // first connect.
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestConnectionPriority failed (non-fatal)", e)
                    }
                    // Negotiate MTU 247 before service discovery so the larger
                    // payload is in effect by the time we start receiving
                    // 88-byte extended-PVT frames. The official RaceBox app
                    // does the same handshake (btsnoop 2026-05-13).
                    if (!g.requestMtu(DESIRED_MTU)) {
                        Log.w(TAG, "requestMtu returned false, falling back to discoverServices directly")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status $status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) {
                        gatt = null
                        rxCharacteristic = null
                        pendingInitWrites.clear()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU now $mtu (status=$status); discovering services")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                disconnect()
                return
            }
            val service = g.getService(NUS_SERVICE_UUID)
            val tx = service?.getCharacteristic(NUS_TX_UUID)
            val rx = service?.getCharacteristic(NUS_RX_UUID)
            if (tx == null) {
                Log.e(TAG, "NUS TX characteristic not found")
                disconnect()
                return
            }
            rxCharacteristic = rx
            if (!g.setCharacteristicNotification(tx, true)) {
                Log.e(TAG, "Failed to enable notifications on TX characteristic")
                disconnect()
                return
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                // No CCCD on this device — proceed straight to init writes.
                kickInitWrite()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                Log.i(TAG, "TX CCCD write status=$status; ${pendingInitWrites.size} init write(s) queued")
                // Mark connected as soon as notifications are wired. The
                // init writes happen after but the rider already has live
                // data flowing in case the writes fail.
                _connectionState.value = ConnectionState.CONNECTED
                kickInitWrite()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (ch.uuid == NUS_RX_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Init write failed (status=$status); skipping remaining ${pendingInitWrites.size}")
                    pendingInitWrites.clear()
                    return
                }
                kickInitWrite()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotification(ch.uuid, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(ch.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun kickInitWrite() {
        val g = gatt ?: return
        val rx = rxCharacteristic ?: return
        if (pendingInitWrites.isEmpty()) return
        val next = pendingInitWrites.removeFirst()
        // RaceBox accepts write-with-response on its RX char, matching the
        // official app's behaviour. Some stacks reject write-no-response on
        // a char that advertises both; sticking to WRITE_TYPE_DEFAULT (with
        // response) keeps us safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(rx, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            rx.value = next
            @Suppress("DEPRECATION")
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(rx)
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid != NUS_TX_UUID || value.isEmpty()) return
        val sample = activeAdapter?.decode(value) ?: return
        _samples.tryEmit(sample)
    }
}
