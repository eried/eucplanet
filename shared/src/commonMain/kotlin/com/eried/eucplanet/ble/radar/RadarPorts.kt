package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.RadarVendor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

// What the shared radar code needs from the phone's Bluetooth. Android:
// RadarConnectionManager and RadarScanner in :app. iOS: not yet.

/**
 * The GATT link to a paired radar. Opens the connection, subscribes to the
 * adapter's notify characteristic (and the standard battery level when the
 * device has it) and hands every notification on, tagged with the
 * characteristic it came from.
 */
interface RadarLink {
    val connectionState: StateFlow<ConnectionState>

    /** Raw notification frames, paired with the source characteristic UUID. */
    val notifications: SharedFlow<RadarNotification>

    /** Connect to [address] and keep reconnecting after an unexpected drop. */
    fun connect(address: String, adapter: RadarAdapter)

    /** Deliberate, rider-initiated disconnect: stops auto-reconnect too. */
    fun disconnect()
}

/** One notification frame, paired with the source characteristic UUID. */
data class RadarNotification(val uuid: Uuid, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadarNotification) return false
        return uuid == other.uuid && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * uuid.hashCode() + bytes.contentHashCode()
}

/** A scan result tagged with whichever adapter claimed it. */
data class RadarScanResult(
    val device: BleDevice,
    val vendor: RadarVendor
)

/** The pairing scan for radars, independent of the wheel and GPS scans. */
interface RadarScan {
    fun scan(): Flow<RadarScanResult>

    /** True only when the adapter exists and Bluetooth is turned on. */
    fun isBluetoothEnabled(): Boolean

    fun stop()

    /**
     * Calls [listener] with false when Bluetooth turns (or is turning) off
     * and with true when it is back on. Returns the call that stops watching.
     */
    fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit
}
