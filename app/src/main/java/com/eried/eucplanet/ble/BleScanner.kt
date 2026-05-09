package com.eried.eucplanet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    /**
     * Start a BLE scan and emit one [BleDevice] per advertisement match.
     *
     * @param showAll  when true, every named peripheral is forwarded (matches
     *                 WheelLog's behaviour). When false, only names that match
     *                 a known wheel prefix are forwarded — useful for keeping
     *                 the scan list short and free of unrelated devices in
     *                 typical usage.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevices(showAll: Boolean = false): Flow<BleDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not available")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (showAll || isLikelyWheel(name)) {
                    trySend(BleDevice(
                        name = name,
                        address = result.device.address,
                        rssi = result.rssi
                    ))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("BLE scan failed with error: $errorCode"))
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)

        awaitClose {
            scanner.stopScan(callback)
            scanCallback = null
        }
    }

    /**
     * BLE-name allowlist for the default ("known wheels only") scan mode.
     *
     * V14 advertises as `Adventure-<id>`, P6 as `P6-<id>`. The InMotion V2
     * registry covers V8 through V13 — those wheels broadcast as
     * `V<digits><letters?>-<id>` (V11-…, V11Y-…, V12HS-…, V13Pro-…) per
     * community captures. We don't have one of each here to confirm, so
     * the regex errs inclusive. The generic `InMotion` prefix catches
     * anything that ships with the brand name in the advertised name.
     * Users with an unusual name can flip the "show all" switch on the
     * scan screen.
     */
    private fun isLikelyWheel(name: String): Boolean {
        if (name.startsWith("Adventure-")) return true
        if (name.startsWith("P6-")) return true
        if (name.startsWith("InMotion")) return true
        // V8-…, V9-…, V10-…, V11-…, V11Y-…, V12HS-…, V13Pro-…: leading V
        // followed by at least one digit and at least one more character
        // (separator, model letter, or further digit). Rejects bare "V" /
        // "V1" / "V12" beacons, accepts the InMotion V2 family.
        if (name.length < 3 || name[0] != 'V' || !name[1].isDigit()) return false
        var i = 2
        while (i < name.length && name[i].isDigit()) i++
        return i < name.length
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }
}
