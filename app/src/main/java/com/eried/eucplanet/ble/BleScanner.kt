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
        // InMotion V1 legacy names: "IM<digits>" (R-series rebrands), "L6-",
        // "Lively-", "Glide" / "Solowheel". V8 / V10 family already matched
        // by the V<digits> regex above.
        if (name.length >= 3 && (name[0] == 'I' || name[0] == 'i') &&
            (name[1] == 'M' || name[1] == 'm') && name[2].isDigit()) return true
        if (name.startsWith("L6-", ignoreCase = true)) return true
        if (name.startsWith("Lively", ignoreCase = true)) return true
        if (name.startsWith("Glide", ignoreCase = true) ||
            name.startsWith("Solowheel", ignoreCase = true)) return true
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
        // Veteran (LeaperKim). Match the model tokens AND the brand's own
        // serial-style BLE prefixes — many wheels advertise as `LK<digits>`
        // (the rider's Lynx S showed up as `LK20712`, the Oryx as `LK19957`)
        // with no model token in the name, so the scan filter has to know
        // about the prefixes too. CompositeWheelAdapter.pickAdapter already
        // routes these to the Veteran adapter at connect time; the same
        // patterns belong here so they're not filtered out before scan.
        val nl = name.lowercase()
        if ("sherman" in nl || "patton" in nl || "abrams" in nl ||
            "oryx" in nl || "nosfet" in nl || "leaperkim" in nl ||
            Regex("\\blynx\\b").containsMatchIn(nl) ||
            Regex("^lk\\d").containsMatchIn(nl)) return true
        // Ninebot / Segway-Ninebot. Both protocol families (Z and legacy)
        // start with the brand prefix; "ZN<serial>" is the bare-firmware
        // form on some Z6 wheels; "MiniPlus<serial>" advertises Z protocol
        // despite the legacy-style name.
        if (name.startsWith("Ninebot", ignoreCase = true) ||
            name.startsWith("Segway", ignoreCase = true)) return true
        if (Regex("^ZN\\d", RegexOption.IGNORE_CASE).containsMatchIn(name)) return true
        if (name.startsWith("MiniPLUS", ignoreCase = true) ||
            name.startsWith("Mini Plus", ignoreCase = true)) return true
        return false
    }

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
