package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// What the shared external GPS code needs from the phone's Bluetooth.
// Android: ExternalGpsConnectionManager and ExternalGpsScanner in :app.
// iOS: IosExternalGpsLink and IosExternalGpsScan.

/**
 * The GATT link to a paired GPS box. Opens the connection, subscribes to the
 * adapter's notify characteristic, writes the adapter's init frames one at a
 * time (each acknowledged), then reads the adapter's poll characteristic on
 * its interval. Every notification goes through [ExternalGpsAdapter.decode]
 * and every poll result through [ExternalGpsAdapter.onPollResult]; decoded
 * fixes come out of [samples].
 */
interface ExternalGpsLink {
    val connectionState: StateFlow<ConnectionState>
    val samples: SharedFlow<ExternalGpsSample>

    /** Connect to [address] and keep reconnecting after an unexpected drop. */
    fun connect(address: String, adapter: ExternalGpsAdapter, initWrites: List<ByteArray>)

    /** Deliberate, rider-initiated disconnect: stops auto-reconnect too. */
    fun disconnect()
}

/** A scan result tagged with whichever adapter claimed it. */
data class ExternalGpsScanResult(
    val device: BleDevice,
    val source: ExternalGpsSource
)

/**
 * The pairing scan for GPS boxes, independent of the wheel scan so the rider
 * can look for a box without disrupting a wheel, and so wheel and GPS results
 * never mix in the pairing UI.
 */
interface ExternalGpsScan {
    fun scan(): Flow<ExternalGpsScanResult>

    /** True only when the adapter exists and Bluetooth is turned on. */
    fun isBluetoothEnabled(): Boolean

    fun stop()

    /**
     * Calls [listener] with false when Bluetooth turns (or is turning) off
     * and with true when it is back on. Returns the call that stops watching.
     */
    fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit
}
