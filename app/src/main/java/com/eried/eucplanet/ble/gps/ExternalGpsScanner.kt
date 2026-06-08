package com.eried.eucplanet.ble.gps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.data.model.ExternalGpsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE scanner dedicated to external GPS boxes. Walks every advertisement and
 * routes it through the registered [ExternalGpsAdapter] set; emits the device
 * tagged with whichever adapter claimed it. Independent of [BleScanner] so
 * the user can scan for a GPS box without disrupting an active wheel scan,
 * and so wheel-vs-GPS results never get mixed in the pairing UI.
 */
data class ExternalGpsScanResult(
    val device: BleDevice,
    val source: ExternalGpsSource
)

@Singleton
class ExternalGpsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapters: Set<@JvmSuppressWildcards ExternalGpsAdapter>
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ExternalGpsScanResult> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not available")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val matched = adapters.firstOrNull { it.matches(name) } ?: return
                trySend(
                    ExternalGpsScanResult(
                        device = BleDevice(
                            name = name,
                            address = result.device.address,
                            rssi = result.rssi
                        ),
                        source = matched.source
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("External GPS scan failed: $errorCode"))
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

    /** True only when the adapter exists and Bluetooth is turned on. Callers
     *  check this before [scan] so a tap with Bluetooth off surfaces a prompt
     *  instead of crashing on the null leScanner thrown inside the flow. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun stop() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }
}
