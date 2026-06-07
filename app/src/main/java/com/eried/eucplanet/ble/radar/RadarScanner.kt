package com.eried.eucplanet.ble.radar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.data.model.RadarVendor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE scanner dedicated to radar devices. Walks every advertisement and
 * routes the name through the registered [RadarAdapter] set; emits the
 * device tagged with whichever adapter claimed it.
 *
 * Independent of [com.eried.eucplanet.ble.BleScanner] and
 * [com.eried.eucplanet.ble.gps.ExternalGpsScanner] so the rider can scan
 * for a radar without disrupting an active wheel or GPS scan, and so the
 * three result types never mix in the pairing UI.
 *
 * No service-UUID filter is set on the scan: Varia's advertising frame
 * doesn't always include the 128-bit RDR UUID (it's truncated for power),
 * so a name-prefix match is the safer net.
 */
data class RadarScanResult(
    val device: BleDevice,
    val vendor: RadarVendor
)

@Singleton
class RadarScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapters: Set<@JvmSuppressWildcards RadarAdapter>
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun scan(): Flow<RadarScanResult> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not available")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val matched = adapters.firstOrNull { it.matches(name) } ?: return
                trySend(
                    RadarScanResult(
                        device = BleDevice(
                            name = name,
                            address = result.device.address,
                            rssi = result.rssi
                        ),
                        vendor = matched.vendor
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("Radar scan failed: $errorCode"))
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
