package com.eried.eucplanet.ui.scan

import com.eried.eucplanet.ble.BleDevice
import kotlinx.coroutines.flow.Flow

// What ScanViewModel needs from the platform, like DashboardPorts.

/** The Bluetooth LE scan (BleScanner on Android). */
interface WheelScanner {
    /** One [BleDevice] per advertisement; every named device when [showAll]. */
    fun scanForDevices(showAll: Boolean): Flow<BleDevice>
    fun isBluetoothEnabled(): Boolean
    fun stopScan()
}

interface ScanPlatform {
    /** The permissions a scan still needs, as the platform names them. */
    fun missingScanPermissions(): List<String>

    /**
     * Calls [listener] with false when Bluetooth turns (or is turning) off and
     * true when it is back on. The returned function stops listening.
     */
    fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit

    /** False when connecting would fail for a missing permission. */
    fun mayConnect(): Boolean

    /** Starts the connection (the wheel service on Android). */
    fun connect(address: String, name: String)
}
