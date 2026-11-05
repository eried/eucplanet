package com.eried.eucplanet.ui.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.BleScanner
import com.eried.eucplanet.service.WheelService
import kotlinx.coroutines.flow.Flow

// The Android side of ScanPorts, over the classes ScanViewModel used directly.

class AndroidWheelScanner(private val scanner: BleScanner) : WheelScanner {
    override fun scanForDevices(showAll: Boolean): Flow<BleDevice> = scanner.scanForDevices(showAll)
    override fun isBluetoothEnabled(): Boolean = scanner.isBluetoothEnabled()
    override fun stopScan() = scanner.stopScan()
}

class AndroidScanPlatform(private val context: Context) : ScanPlatform {

    private fun requiredScanPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: neverForLocation is declared, so no location needed for scan
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11-: legacy BLE scan requires fine location
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun missingScanPermissions(): List<String> = requiredScanPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    // Android stops delivering scan results when Bluetooth goes off WITHOUT
    // calling onScanFailed, so the scan screen has to hear about it here.
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

    // Same gate the dashboard's auto-connect applies. Without
    // BLUETOOTH_CONNECT the service cannot declare its foreground type,
    // stops itself before startForeground(), and Android then kills the
    // whole app for the broken startForegroundService() promise.
    override fun mayConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    override fun connect(address: String, name: String) {
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_CONNECT
            putExtra(WheelService.EXTRA_ADDRESS, address)
            putExtra(WheelService.EXTRA_NAME, name)
        }
        context.startForegroundService(intent)
    }
}
