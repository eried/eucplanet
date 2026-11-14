package com.eried.eucplanet.tpms

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
import androidx.core.content.ContextCompat

/**
 * The Android side of [TpmsRadio]: the BLE scanner the tyre monitor listens
 * through. The rules (when to search, when to watch, what to adopt) are the
 * shared [TpmsScanner]'s.
 */
class AndroidTpmsRadio(private val context: Context) : TpmsRadio {

    /** The platform callback behind each open [TpmsRadio.Listener]. */
    private val callbacks = HashMap<TpmsRadio.Listener, ScanCallback>()

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun isOn(): Boolean = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner != null

    @SuppressLint("MissingPermission")
    override fun startScan(listener: TpmsRadio.Listener, lowPower: Boolean, addresses: List<String>?) {
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not available")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) =
                listener.onAdvert(advertOf(result))

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { listener.onAdvert(advertOf(it)) }
            }

            override fun onScanFailed(errorCode: Int) {
                synchronized(callbacks) { callbacks.remove(listener) }
                listener.onFailed(errorCode)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(
                // Low latency while hunting, because these broadcast in short
                // bursts and a slower mode misses most of them.
                //
                // Balanced once the sensor is known, not low power. Low power
                // listens about a tenth of the time, and a cap that speaks for
                // a few seconds when the pressure moves and then says nothing
                // for an hour is exactly the signal that slips through a duty
                // cycle like that. The vendor's own app runs low latency the
                // whole time; balanced is the middle that still catches a
                // burst without holding the radio open all day.
                if (lowPower) ScanSettings.SCAN_MODE_BALANCED
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()
        // An address the builder refuses drops the filter, so the scan hears
        // everything rather than nothing.
        val filters = addresses?.let { list ->
            runCatching { list.map { ScanFilter.Builder().setDeviceAddress(it).build() } }.getOrNull()
        }
        synchronized(callbacks) { callbacks[listener] = cb }
        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: Throwable) {
            synchronized(callbacks) { callbacks.remove(listener) }
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan(listener: TpmsRadio.Listener) {
        val cb = synchronized(callbacks) { callbacks.remove(listener) } ?: return
        adapter()?.bluetoothLeScanner?.stopScan(cb)
    }

    /**
     * The monitor is not a rider standing on a screen, so it never asks for
     * Bluetooth; it picks the watching back up when the radio returns.
     */
    override fun watchBluetooth(onChange: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> onChange(false)
                    BluetoothAdapter.STATE_ON -> onChange(true)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @SuppressLint("MissingPermission")
    private fun advertOf(result: ScanResult): TpmsAdvert {
        val rec = result.scanRecord
        val manufacturer = ArrayList<Pair<Int, ByteArray>>()
        rec?.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                val raw = sparse.valueAt(i) ?: continue
                manufacturer += sparse.keyAt(i) to raw
            }
        }
        val serviceData = ArrayList<Pair<String, ByteArray>>()
        rec?.serviceData?.forEach { (uuid, bytes) -> serviceData += uuid.uuid.toString() to bytes }
        return TpmsAdvert(
            address = result.device.address,
            name = rec?.deviceName ?: runCatching { result.device.name }.getOrNull(),
            rssi = result.rssi,
            manufacturer = manufacturer,
            serviceData = serviceData,
            serviceUuids = rec?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
        )
    }
}
