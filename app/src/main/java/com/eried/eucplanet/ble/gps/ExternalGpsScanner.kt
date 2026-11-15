package com.eried.eucplanet.ble.gps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.eried.eucplanet.ble.BleDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE scanner dedicated to external GPS boxes. Walks every advertisement and
 * routes it through the registered [ExternalGpsAdapter] set; emits the device
 * tagged with whichever adapter claimed it. Independent of [BleScanner] so
 * the user can scan for a GPS box without disrupting an active wheel scan,
 * and so wheel-vs-GPS results never get mixed in the pairing UI.
 *
 * The Android side of the shared [ExternalGpsScan].
 */
class ExternalGpsScanner(
    private val context: Context,
    private val adapters: Set<ExternalGpsAdapter>
) : ExternalGpsScan {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<ExternalGpsScanResult> = callbackFlow {
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
    override fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun stop() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    // Detects Bluetooth toggling while the GPS pairing scan is open, for
    // ExternalGpsViewModel's "Bluetooth is off" hint and its resume.
    override fun watchBluetooth(listener: (Boolean) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> listener(false)
                    BluetoothAdapter.STATE_ON -> listener(true)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return { context.unregisterReceiver(receiver) }
    }
}
