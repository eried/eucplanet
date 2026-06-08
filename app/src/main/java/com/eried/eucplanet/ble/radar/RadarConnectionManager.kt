package com.eried.eucplanet.ble.radar

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
import com.eried.eucplanet.ble.BleAutoReconnector
import com.eried.eucplanet.ble.ConnectionState
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
 * GATT instance dedicated to a rear-view radar (Garmin Varia today).
 * Independent of [com.eried.eucplanet.ble.BleConnectionManager] and
 * [com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager] so the wheel,
 * the external GPS box, and the radar each hold their own connection
 * without interfering ,  Android allows multiple concurrent GATT clients.
 *
 * Responsibilities: open the connection, request a larger MTU (so a frame
 * with the full 6 cars fits in a single notification rather than the
 * default 23-byte MTU's 6-car-fragmented shape), discover services,
 * subscribe to the adapter's notify characteristic, optionally subscribe
 * to the standard battery service, and forward decoded frames to the
 * repository as raw byte arrays paired with their characteristic UUID.
 */
@Singleton
class RadarConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RadarConn"

        /** Standard org.bluetooth.service.battery_service. */
        private val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        /** Standard org.bluetooth.characteristic.battery_level. */
        private val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Pick a comfortable MTU for the largest possible Varia frame:
         * 1 header + 3 bytes/car * 8 cars = 25 bytes payload, plus 3-byte
         * ATT overhead. 100 is well above that and matches what the
         * official Varia app negotiates.
         */
        private const val DESIRED_MTU = 100
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Decoded notification frames, paired with the source characteristic UUID. */
    data class RawNotification(val uuid: UUID, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawNotification) return false
            return uuid == other.uuid && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = 31 * uuid.hashCode() + bytes.contentHashCode()
    }

    private val _notifications = MutableSharedFlow<RawNotification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<RawNotification> = _notifications.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var activeAdapter: RadarAdapter? = null
    @Volatile private var currentAddress: String? = null

    // Auto-reconnect (mirrors the wheel): scan-then-connect when Bluetooth
    // returns or after an unexpected drop, instead of leaving the radar stuck
    // until the rider taps Reconnect.
    private val reconnector = BleAutoReconnector(
        context = context,
        tag = TAG,
        isConnected = { _connectionState.value == ConnectionState.CONNECTED },
        reconnect = { address -> connectInternal(address) },
        forceDisconnect = { forceDisconnect() }
    ).also { it.start() }

    /**
     * CCCDs queued for the post-discover subscribe sequence. The GATT API
     * only allows one in-flight descriptor write per connection, so we pop
     * one at a time and wait for [onDescriptorWrite] before the next.
     */
    private val pendingCccdWrites = ArrayDeque<BluetoothGattCharacteristic>()

    @SuppressLint("MissingPermission")
    fun connect(address: String, adapter: RadarAdapter) {
        activeAdapter = adapter
        currentAddress = address
        reconnector.arm(address)
        connectInternal(address)
    }

    /** GATT-only (re)connect using the remembered [activeAdapter]; does not
     *  touch the reconnector, so it's safe to call from the reconnect path. */
    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        teardownGatt()
        // Don't issue a GATT connect while Bluetooth is off - it fails (status
        // 133/147) and churns; the reconnector's STATE_ON handler drives it.
        if (bluetoothManager.adapter?.isEnabled != true) {
            Log.i(TAG, "connect($address) deferred: Bluetooth is off")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val device: BluetoothDevice = try {
            bluetoothManager.adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad device address $address", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to ${activeAdapter?.vendor?.displayName} @ $address")
        gatt = device.connectGatt(context, /* autoConnect = */ false, callback)
    }

    /** Deliberate, user-initiated disconnect: stop auto-reconnect too. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnector.cancel()
        currentAddress = null
        activeAdapter = null
        teardownGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Bluetooth went off: drop the dead GATT but keep the target so the
     *  reconnector can re-arm when it comes back. */
    @SuppressLint("MissingPermission")
    private fun forceDisconnect() {
        teardownGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun teardownGatt() {
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        pendingCccdWrites.clear()
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected (status=$status), requesting MTU $DESIRED_MTU")
                    _connectionState.value = ConnectionState.INITIALIZING
                    if (!g.requestMtu(DESIRED_MTU)) {
                        Log.w(TAG, "requestMtu returned false, discovering services anyway")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status $status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) {
                        gatt = null
                        pendingCccdWrites.clear()
                    }
                    // Auto-reconnect on an unexpected drop (status != 0); a
                    // deliberate disconnect() already cancelled the reconnector.
                    reconnector.onUnexpectedDisconnect(status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU now $mtu (status=$status); discovering services")
            @SuppressLint("MissingPermission")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val adapter = activeAdapter
            if (status != BluetoothGatt.GATT_SUCCESS || adapter == null) {
                Log.e(TAG, "Service discovery failed: status=$status adapter=${adapter == null}")
                disconnect()
                return
            }
            pendingCccdWrites.clear()

            val radarService = g.getService(adapter.serviceUuid)
            val notifyChar = radarService?.getCharacteristic(adapter.notifyCharacteristicUuid)
            if (notifyChar == null) {
                Log.e(TAG, "Radar notify characteristic ${adapter.notifyCharacteristicUuid} not found")
                disconnect()
                return
            }
            if (!g.setCharacteristicNotification(notifyChar, true)) {
                Log.e(TAG, "Failed to enable notify on radar characteristic")
                disconnect()
                return
            }
            notifyChar.getDescriptor(CCCD_UUID)?.let { pendingCccdWrites += it.characteristic }

            // Battery service is optional. Varia exposes the standard
            // 0x180F/0x2A19 pair, but if a future radar doesn't, we just
            // skip this branch ,  the connection state machine doesn't
            // depend on the battery subscription succeeding.
            val batteryService = g.getService(BATTERY_SERVICE_UUID)
            val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL_UUID)
            if (batteryChar != null) {
                // Battery level may be readable, notify, or both. We try
                // notify first; the periodic read kicks in once we land in
                // CONNECTED so we get a value even when the device only
                // re-notifies on a level change.
                val supportsNotify = (batteryChar.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                if (supportsNotify) {
                    g.setCharacteristicNotification(batteryChar, true)
                    batteryChar.getDescriptor(CCCD_UUID)?.let { pendingCccdWrites += it.characteristic }
                }
            }

            kickNextCccdWrite(g)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                if (pendingCccdWrites.isEmpty()) {
                    // All notifications wired. Move to CONNECTED and
                    // kick a one-shot battery read so the UI has a value
                    // even when the device only re-notifies on change.
                    _connectionState.value = ConnectionState.CONNECTED
                    val batteryService = g.getService(BATTERY_SERVICE_UUID)
                    batteryService?.getCharacteristic(BATTERY_LEVEL_UUID)?.let {
                        g.readCharacteristic(it)
                    }
                } else {
                    kickNextCccdWrite(g)
                }
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

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleNotification(ch.uuid, ch.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleNotification(ch.uuid, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun kickNextCccdWrite(g: BluetoothGatt) {
        if (pendingCccdWrites.isEmpty()) return
        val ch = pendingCccdWrites.removeFirst()
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
            // No CCCD on this characteristic, recurse so we don't hang.
            kickNextCccdWrite(g)
            return
        }
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

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return
        _notifications.tryEmit(RawNotification(uuid, value))
    }
}
