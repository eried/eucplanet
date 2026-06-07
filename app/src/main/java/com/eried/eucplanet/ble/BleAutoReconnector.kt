package com.eried.eucplanet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared auto-reconnect for the secondary single-device GATT managers (radar,
 * external GPS), mirroring what [BleConnectionManager] does for the wheel.
 *
 * Holds a Bluetooth-state receiver and, when Bluetooth returns or after an
 * unexpected disconnect, scans for the paired device by address and fires
 * [reconnect] only once the device is actually advertising. This is the
 * reliable path: a blind direct connect times out with status 147 the moment
 * after a Bluetooth toggle (the device isn't connectable in its 30s window),
 * and autoConnect=true is slow/flaky on some stacks.
 *
 * The owning manager calls [start] once, [arm] on connect, [cancel] on a
 * deliberate disconnect, and [onUnexpectedDisconnect] from its GATT
 * disconnect callback. The callbacks let this stay agnostic of each manager's
 * connect signature (radar takes an adapter, GPS also takes init writes).
 */
@SuppressLint("MissingPermission")
class BleAutoReconnector(
    private val context: Context,
    private val tag: String,
    /** True when the manager is currently CONNECTED. */
    private val isConnected: () -> Boolean,
    /** Re-open the GATT connection to the (now-advertising) address. */
    private val reconnect: (address: String) -> Unit,
    /** Tear the dead connection down to DISCONNECTED (adapter went off). */
    private val forceDisconnect: () -> Unit,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var target: String? = null
    @Volatile private var shouldReconnect = false
    private var scanCallback: ScanCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    forceDisconnect()
                }
                BluetoothAdapter.STATE_ON -> onBluetoothOn()
            }
        }
    }

    /** Register the Bluetooth-state receiver. Call once (manager init). */
    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Arm auto-reconnect for [address] (called on each manager connect). */
    fun arm(address: String) {
        target = address
        shouldReconnect = true
    }

    /** Stop auto-reconnect entirely (a deliberate, user-initiated disconnect). */
    fun cancel() {
        shouldReconnect = false
        target = null
        stopScan()
    }

    /**
     * From the manager's GATT disconnect callback: schedule a reconnect when
     * the drop wasn't deliberate (status != 0). Bluetooth must be on, otherwise
     * we wait for the STATE_ON broadcast instead.
     */
    fun onUnexpectedDisconnect(status: Int) {
        val address = target ?: return
        if (!shouldReconnect || status == 0) return
        scope.launch {
            delay(2000)
            if (eligible(address)) scanThenConnect(address)
        }
    }

    private fun onBluetoothOn() {
        val address = target ?: return
        if (!shouldReconnect || isConnected()) return
        scope.launch {
            // The BLE stack isn't ready the instant the adapter reports ON.
            delay(1500)
            if (eligible(address)) scanThenConnect(address)
        }
    }

    private fun eligible(address: String): Boolean =
        shouldReconnect &&
            target == address &&
            !isConnected() &&
            bluetoothManager.adapter?.isEnabled == true

    private fun scanThenConnect(address: String) {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            reconnect(address)
            return
        }
        stopScan()
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(tag, "Reconnect scan found $address; connecting")
                stopScan()
                reconnect(address)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(tag, "Reconnect scan failed ($errorCode); trying direct connect")
                stopScan()
                reconnect(address)
            }
        }
        scanCallback = cb
        try {
            Log.i(tag, "Scanning to reconnect to $address")
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            Log.w(tag, "Reconnect scan start threw; direct connect", e)
            scanCallback = null
            reconnect(address)
            return
        }
        // Don't scan forever if the device never shows (off / out of range).
        scope.launch {
            delay(60_000)
            if (scanCallback === cb) {
                Log.i(tag, "Reconnect scan timed out for $address")
                stopScan()
            }
        }
    }

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }
}
