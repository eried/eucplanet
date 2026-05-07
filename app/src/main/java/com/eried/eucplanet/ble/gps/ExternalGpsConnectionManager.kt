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
 * Second GATT instance dedicated to an external BLE GPS box. Independent of
 * [com.eried.eucplanet.ble.BleConnectionManager] so the wheel and the GPS box
 * can each hold their own connection without interfering.
 *
 * Responsibility is intentionally narrow: open connection, discover services,
 * subscribe to the Nordic UART TX characteristic, forward every raw
 * notification to the active [ExternalGpsAdapter], and re-emit any
 * [ExternalGpsSample] the adapter produces. No command writes — RaceBox
 * starts streaming on subscribe and we have no need to send anything back.
 */
@Singleton
class ExternalGpsConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExtGpsConn"
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<ExternalGpsSample>(extraBufferCapacity = 16)
    val samples: SharedFlow<ExternalGpsSample> = _samples.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var activeAdapter: ExternalGpsAdapter? = null

    @SuppressLint("MissingPermission")
    fun connect(address: String, adapter: ExternalGpsAdapter) {
        disconnect()
        activeAdapter = adapter
        val device: BluetoothDevice = try {
            bluetoothManager.adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad device address $address", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to ${adapter.source.displayName} @ $address")
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
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    _connectionState.value = ConnectionState.INITIALIZING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status $status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                }
            }
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
            if (tx == null) {
                Log.e(TAG, "NUS TX characteristic not found")
                disconnect()
                return
            }
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
            }

            // Adapter init commands (RaceBox needs none, defaults to empty).
            activeAdapter?.initCommands()?.forEach { _ -> /* no RX writes for now */ }

            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Subscribed to ${activeAdapter?.source?.displayName} stream")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // Pre-Tiramisu callback. The Tiramisu+ overload below routes to the
            // same handler so adapters always see the bytes once.
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

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (uuid != NUS_TX_UUID || value.isEmpty()) return
        val sample = activeAdapter?.decode(value) ?: return
        _samples.tryEmit(sample)
    }
}
