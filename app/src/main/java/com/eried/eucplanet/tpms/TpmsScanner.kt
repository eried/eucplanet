package com.eried.eucplanet.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for tire-pressure sensors that broadcast rather than connect.
 *
 * A valve-cap TPMS is a coin cell in a plastic cap: it wakes, shouts a reading
 * into an advertisement and goes back to sleep. It never pairs and never
 * accepts a connection, so there is no GATT service to read and nothing shows
 * up in the phone's bonded list. The only way to hear one is to scan and read
 * the advertising payload.
 *
 * This is also how the reading gets decoded in the first place. A sensor's
 * packet format is not published, so the way in is to watch the bytes at a
 * pressure you have measured with a gauge and find the ones that match. Every
 * payload is logged with its manufacturer and service data intact, so a
 * capture at a known pressure is enough to identify the field.
 */
@Singleton
class TpmsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tpms: TpmsRepository,
) {

    /** One heard advertisement, newest first, for the settings list. */
    data class Seen(
        val address: String,
        val name: String?,
        val rssi: Int,
        /** Manufacturer id to payload hex, as advertised. */
        val manufacturer: Map<Int, String>,
        /** Service UUID to payload hex. */
        val service: Map<String, String>,
        val atMs: Long,
        /** First time this address was heard, so the list can stop jumping. */
        val firstSeenMs: Long = atMs,
        /**
         * The sensor signature: a payload that opens with the advertiser's own
         * MAC. Valve-cap TPMS units do this so a receiver can tell four
         * identical caps apart, and almost nothing else in a garage does. It
         * is what separates two sensors from forty phones, laptops and
         * earbuds.
         */
        val looksLikeSensor: Boolean = false,
        /**
         * Pressure in kPa when a decoder recognised this payload.
         *
         * This, not the address, is what identifies a sensor. These units ship
         * with whatever MAC the factory burned in, so there is no list to
         * match against: a device is a tyre sensor when it broadcasts
         * something that decodes as a tyre pressure and carries its own MAC
         * the way these do. Behaviour, not identity.
         */
        val kpa: Float? = null,
    )

    private val _seen = MutableStateFlow<List<Seen>>(emptyList())
    val seen: StateFlow<List<Seen>> = _seen.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /**
     * What the scan is doing, in the rider's words, the way Flic reports it.
     *
     * A spinner alone cannot say "Bluetooth is off", and that was the state a
     * tap used to land in silently: start() found no scanner, returned, and
     * left the button looking untouched. A scan that cannot start has to say
     * so.
     */
    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    /** Seconds left in the current scan window, so the wait has an end in sight. */
    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()

    private var callback: ScanCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        // A radio that is off is the common reason a scan finds nothing, and
        // silently returning made the button look broken instead of the
        // Bluetooth setting look off.
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _scanning.value = false
            _scanStatus.value = context.getString(R.string.scan_bluetooth_off_title)
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { record(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "TPMS scan failed: $errorCode")
                _scanning.value = false
                _scanStatus.value = context.getString(R.string.tpms_scan_failed)
            }
        }
        callback = cb
        // No filter: the sensor is unknown, which is the point. Low latency
        // because these broadcast in short bursts and a slower mode misses
        // most of them.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, cb) }
            .onSuccess {
                _scanning.value = true
                _scanStatus.value = ""
                startWindow()
            }
            .onFailure {
                Log.w(TAG, "TPMS scan could not start", it)
                callback = null
                _scanStatus.value = context.getString(R.string.tpms_scan_failed)
            }
    }

    /**
     * A scan that ends by itself.
     *
     * These sensors answer within seconds when they are awake, so a radio left
     * running past that is draining a battery to hear nothing. The countdown is
     * also the honest version of a spinner: it says how long the waiting lasts
     * and admits when it found nothing, instead of turning forever.
     */
    private fun startWindow() {
        window?.cancel()
        window = scope.launch {
            var left = SCAN_WINDOW_S
            while (left > 0 && _scanning.value) {
                _secondsLeft.value = left
                delay(1000)
                left--
            }
            if (_scanning.value) {
                val foundNothing = tpms.pairedAddress.value == null
                stop()
                if (foundNothing) {
                    _scanStatus.value = context.getString(R.string.tpms_scan_none_found)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        window?.cancel()
        window = null
        _secondsLeft.value = 0
        val cb = callback ?: return
        callback = null
        _scanning.value = false
        _scanStatus.value = ""
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(result: ScanResult) {
        val rec = result.scanRecord
        val manu = HashMap<Int, String>()
        var decoded: Float? = null
        rec?.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                val id = sparse.keyAt(i)
                val raw = sparse.valueAt(i) ?: continue
                manu[id] = raw.toHex()
                if (decoded == null) {
                    decoded = LyTpmsDecoder.pressureKpa(id, raw, result.device.address)
                }
            }
        }
        val svc = HashMap<String, String>()
        rec?.serviceData?.forEach { (uuid, bytes) -> svc[uuid.uuid.toString()] = bytes.toHex() }
        // Nothing to say without a payload: a bare advertisement is every
        // other device in the room.
        if (manu.isEmpty() && svc.isEmpty()) return

        val address = result.device.address
        val now = System.currentTimeMillis()
        val macHex = address.replace(":", "").uppercase()
        val previous = _seen.value.firstOrNull { it.address == address }
        val seen = Seen(
            address = address,
            name = rec?.deviceName ?: runCatching { result.device.name }.getOrNull(),
            rssi = result.rssi,
            manufacturer = manu,
            service = svc,
            atMs = now,
            firstSeenMs = previous?.firstSeenMs ?: now,
            // A decoded pressure is proof. The MAC-prefix shape is only a
            // hint, kept because it is how an undecoded sensor still shows up
            // as worth looking at while a new model is being worked out.
            looksLikeSensor = decoded != null ||
                manu.values.any { it.startsWith(macHex) } ||
                svc.values.any { it.startsWith(macHex) },
            kpa = decoded,
        )
        // Keyed by address and ordered by when it was FIRST heard. Ordering by
        // the newest packet made the list reshuffle several times a second,
        // which is unreadable and makes a row impossible to tap.
        _seen.value = (_seen.value.filterNot { it.address == address } + seen)
            .sortedBy { it.firstSeenMs }
            .take(MAX_SEEN)

        // The decode trail. Service mode keeps it in the diagnostics capture;
        // the log line is there for a wired session.
        val line = "TPMS adv ${seen.address} ${seen.name ?: "(no name)"} rssi=${seen.rssi} " +
            "manu=${manu.entries.joinToString(",") { "0x%04X:%s".format(it.key, it.value) }} " +
            "svc=${svc.entries.joinToString(",") { "${it.key}:${it.value}" }}"
        // Every packet from a candidate, because that is the decode trail;
        // one line per new address otherwise, so the log stays readable.
        if (seen.looksLikeSensor) {
            DiagnosticsLogger.note(line)
        }
        // Adopted on sight. A rider who opened this screen wants their tyre
        // read, not a list of radio addresses to choose between, and a packet
        // that decodes as a pressure has already proved which device it is.
        decoded?.let {
            val hadSensor = tpms.pairedAddress.value != null
            tpms.submitPaired(it, address, now)
            // Found it, so stop looking. Flic ends its scan on pair, and a
            // radio left running after the answer arrived costs battery for
            // nothing. Only on the sensor that was actually adopted: a second
            // cap in the room must not end a scan that has not found the
            // rider's yet.
            if (!hadSensor && tpms.pairedAddress.value == address) {
                stop()
                _scanStatus.value = context.getString(R.string.tpms_scan_added)
            }
        }
        // Everything, at INFO, while the format is still unknown. The only
        // reliable way to find a pressure field is to diff every payload in
        // range across a pressure the rider actually changed, and a filter
        // applied before the decode is a filter applied on a guess.
        Log.i(TAG, line)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "TpmsScanner"

        /** Enough to see everything in a garage without growing without bound. */
        private const val MAX_SEEN = 60

        /**
         * How long one scan runs.
         *
         * Long enough for a sensor that only reports when the wheel moves, and
         * short enough that a rider who walked away is not still scanning.
         */
        private const val SCAN_WINDOW_S = 30
    }
}
