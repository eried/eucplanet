package com.eried.eucplanet.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    INITIALIZING,
    CONNECTED
}

/**
 * The live link to one wheel: what the rest of the app reads and calls. The
 * wheel protocol (adapters, parsers, commands, the write queue's lanes) is
 * shared; the link under it is per platform because it is mostly the BLE
 * stack's own quirks: [BleConnectionManager] on Android (status-133 retries,
 * scan-filtered reconnects, the no-callback watchdog), CoreBluetooth on iOS.
 *
 * Adding a member here means adding it on both sides; see docs/IOS_SYNC.md.
 */
interface WheelLink {
    val connectionState: StateFlow<ConnectionState>

    /** Connected wheel's display label: the BLE name, " (virtual)" for simulators. */
    val connectedDeviceName: StateFlow<String?>

    /** Address of the current connect target; null after an explicit disconnect. */
    val connectedAddress: StateFlow<String?>

    /** Brand of the connected wheel, from the active adapter. */
    val connectedBrand: StateFlow<String?>

    /** Decoded results from the active wheel adapter. */
    val decodedResults: SharedFlow<DecodeResult>

    /** Live link RSSI in dBm, or null when unknown or disconnected. */
    val rssiDbm: StateFlow<Int?>

    /** When the current connection went CONNECTED, or 0 when there is none. */
    val connectedSinceMs: StateFlow<Long>

    /** The connected wheel's address, or null while not fully connected. */
    fun connectedAddressOrNull(): String?

    /** Re-reads the active adapter's brand (it can change once the model is known). */
    fun refreshBrand()

    fun connect(address: String, name: String? = null, isAuto: Boolean = false)

    fun disconnect()

    /** Queues one write; [kind] decides how it is paced and retried. */
    fun writeCommand(data: ByteArray, kind: BleWriteQueue.Kind = BleWriteQueue.Kind.COMMAND)

    /** Scan screen opened: hold auto-reconnect and drop an auto connection. */
    fun suppressAutoConnect()

    /** Scan screen closed: lift the hold, reconnecting to [reconnectAddress] if still disconnected. */
    fun resumeAutoConnect(reconnectAddress: String?, reconnectName: String?)
}
