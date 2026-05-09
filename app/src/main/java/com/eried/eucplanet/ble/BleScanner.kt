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
     * Recognises the InMotion V2 family (V14 `Adventure-…`, P6 `P6-…`, and
     * the broader `V<digits>…` pattern that covers V11/V12/V13), KingSong
     * (`KS-…`, `KingSong…`, `S22…` / `S20…` / `S18…`, `F22P` / `F18P`),
     * Begode/Gotway (`Gotway_…`, `Begode_…`, plus model-specific prefixes
     * `Master_…`, `RS_…`, `EX_…`, `MSP…`, `MSX…`, `Mten…`, `MCM5…`,
     * `Hero…`, `T3…`, `T4…`) and Veteran (`Sherman…`, `Patton…`, `Lynx…`,
     * `Abrams…`).
     *
     * Users with an unusual name can flip the "show all" switch on the
     * scan screen.
     */
    private fun isLikelyWheel(name: String): Boolean {
        // InMotion V2 family
        if (name.startsWith("Adventure-")) return true
        if (name.startsWith("P6-")) return true
        if (name.startsWith("InMotion")) return true
        // V8-…, V9-…, V10-…, V11-…, V11Y-…, V12HS-…, V13Pro-…: leading V
        // followed by at least one digit and at least one more character.
        if (name.length >= 3 && name[0] == 'V' && name[1].isDigit()) {
            var i = 2
            while (i < name.length && name[i].isDigit()) i++
            if (i < name.length) return true
        }
        // KingSong
        if (name.startsWith("KS-") || name.startsWith("KS ") ||
            name.startsWith("KingSong", ignoreCase = true)) return true
        if (Regex("^S(?:1[6-9]|2[02])(?:\\b|[-_ ])").containsMatchIn(name)) return true
        if (name.startsWith("F18P", ignoreCase = true) ||
            name.startsWith("F22P", ignoreCase = true)) return true
        // Begode/Gotway
        if (name.startsWith("Gotway", ignoreCase = true) ||
            name.startsWith("Begode", ignoreCase = true) ||
            name.startsWith("Master_", ignoreCase = true) ||
            name.startsWith("RS_", ignoreCase = true) || name.startsWith("RS-", ignoreCase = true) ||
            name.startsWith("EX_", ignoreCase = true) || name.startsWith("EX.", ignoreCase = true) ||
            name.startsWith("EX2", ignoreCase = true) ||
            name.startsWith("MSP", ignoreCase = true) || name.startsWith("MSX", ignoreCase = true) ||
            name.startsWith("Mten", ignoreCase = true) || name.startsWith("MCM5", ignoreCase = true) ||
            name.startsWith("Hero", ignoreCase = true) ||
            name.startsWith("T3", ignoreCase = true) || name.startsWith("T4", ignoreCase = true)) return true
        // Veteran
        val nl = name.lowercase()
        if ("sherman" in nl || "patton" in nl || "abrams" in nl ||
            Regex("\\blynx\\b").containsMatchIn(nl)) return true
        return false
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }
}
