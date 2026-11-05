package com.eried.eucplanet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BleScanner(
    private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    /**
     * Start a BLE scan and emit one [BleDevice] per advertisement match.
     *
     * @param showAll  when true, every named peripheral is forwarded.
     *                 When false, only names that match a known wheel
     *                 prefix are forwarded; useful for keeping the scan
     *                 list short and free of unrelated devices in typical
     *                 usage.
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
    // Pure name-based scan filter; the real logic is the top-level
    // [isLikelyWheelName] so it can be unit-tested without a BluetoothAdapter.
    private fun isLikelyWheel(name: String): Boolean = isLikelyWheelName(name)

    /** True only when the adapter exists and Bluetooth is turned on. The scan
     *  screen checks this so a tap on "Start scan" with Bluetooth off prompts
     *  the rider to enable it instead of silently doing nothing. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }
}
