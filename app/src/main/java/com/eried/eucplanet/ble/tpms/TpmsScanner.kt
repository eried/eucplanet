package com.eried.eucplanet.ble.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE scanner dedicated to TPMS advertisements. Runs independently of the
 * wheel / radar / external-GPS scanners so a long-running TPMS scan
 * doesn't disrupt an active wheel connection.
 *
 * Filter strategy: NO `ScanFilter` is passed to `startScan`. Cheap TPMS
 * sensors are inconsistent about whether they include the [FBB0_SERVICE]
 * UUID in advertising (it's often dropped to save bytes) and the known
 * mfg-IDs are a moving target. We do a permissive scan and let
 * [TpmsDecoder] reject everything that isn't a TPMS-shaped payload.
 * The radio overhead is comparable to a UUID-filtered scan in practice,
 * and the decoder is cheap.
 *
 * Each emitted [TpmsScanResult] is one matched advertising frame; the
 * caller (TpmsConnectionManager) is responsible for de-duping by sensor
 * id and rate-limiting downstream consumers.
 */
data class TpmsScanResult(
    val id6Hex: String,
    val rssi: Int,
    val reading: TpmsReading,
)

@Singleton
class TpmsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var activeCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun scan(): Flow<TpmsScanResult> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not available")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val parsed = parse(result) ?: return
                trySend(parsed)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r ->
                    parse(r)?.let { trySend(it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("TPMS scan failed: $errorCode"))
            }
        }
        activeCallback = callback

        val settings = ScanSettings.Builder()
            // Low-latency keeps every advertising packet -- TPMS sensors
            // broadcast at ~10 Hz only while awake, and the awake window
            // is short. Balanced / low-power scan modes drop frames and
            // the rider sees stale readings.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(null, settings, callback)

        awaitClose {
            scanner.stopScan(callback)
            activeCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        activeCallback?.let { scanner.stopScan(it) }
        activeCallback = null
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Extract a [TpmsScanResult] from one advertising frame, or null if
     * the frame doesn't look like a TPMS sensor.
     *
     * We walk every manufacturer-specific record in the scan record (a
     * single advertising frame can theoretically carry multiple mfg
     * blocks) and try the decoder on each. Most sensors only carry one;
     * iterating defensively costs essentially nothing.
     */
    @SuppressLint("MissingPermission")
    private fun parse(result: ScanResult): TpmsScanResult? {
        val record = result.scanRecord ?: return null
        val mfgMap = record.manufacturerSpecificData ?: return null
        if (mfgMap.size() == 0) return null

        // Quick gate: skip frames that advertise NEITHER the FBB0 service
        // NOR any known TPMS mfg id. Saves the decoder a lot of work on
        // crowded BLE environments (a busy office floor has hundreds of
        // unrelated adverts per second).
        val hasFbb0 = record.serviceUuids?.any { it == FBB0_PARCEL } == true
        val mfgIdsSeen = (0 until mfgMap.size()).map { mfgMap.keyAt(it) }
        val hasKnownMfgId = mfgIdsSeen.any { it in KNOWN_TPMS_MFG_IDS }
        if (!hasFbb0 && !hasKnownMfgId) return null

        val nowMs = System.currentTimeMillis()
        for (i in 0 until mfgMap.size()) {
            val mfgId = mfgMap.keyAt(i)
            val data = mfgMap.valueAt(i) ?: continue
            // Re-prepend the mfg id (Android strips it from the value
            // array) so the decoder sees the wire layout.
            val full = ByteArray(data.size + 2)
            full[0] = (mfgId and 0xFF).toByte()
            full[1] = ((mfgId ushr 8) and 0xFF).toByte()
            System.arraycopy(data, 0, full, 2, data.size)

            val reading = TpmsDecoder.decode(full, nowMs) ?: continue

            // Sensor "id6Hex" is the last 3 bytes of the advertising MAC,
            // uppercase, no separators. Matches what's printed on the QR
            // code and the sticker.
            val mac = result.device.address
            val id = mac.replace(":", "").takeLast(6).uppercase()
            return TpmsScanResult(id, result.rssi, reading)
        }
        return null
    }

    companion object {
        /** Service UUID common to cheap aftermarket BLE TPMS sensors. */
        val FBB0_PARCEL: ParcelUuid =
            ParcelUuid.fromString("0000fbb0-0000-1000-8000-00805f9b34fb")

        /**
         * Known TPMS company IDs the decoder recognises. Adding a new
         * brand here AND to [TpmsDecoder.decode] is the only step needed
         * to support it.
         *  - 0x0001: TomTom (used by the BVM / Sykik reference family)
         *  - 0x00AC: observed on the user's "5B611B" sensor; alarm-only
         */
        private val KNOWN_TPMS_MFG_IDS = setOf(0x0001, 0x00AC)
    }
}
