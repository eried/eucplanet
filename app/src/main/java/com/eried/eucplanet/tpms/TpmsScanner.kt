package com.eried.eucplanet.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
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

    private var callback: ScanCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        // The button asks the rider to turn Bluetooth on before it ever gets
        // here, so reaching this with the adapter off means they declined.
        // Nothing to report: they know what they just chose.
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _scanning.value = false
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
                startWindow()
            }
            .onFailure {
                Log.w(TAG, "TPMS scan could not start", it)
                callback = null
                _scanning.value = false
            }
    }

    /**
     * A scan that ends by itself, quietly.
     *
     * A radio left running after the rider walked away drains a battery to
     * hear nothing, so the scan stops on its own after a minute. It says
     * nothing when it does: the button going back to "Scan for sensors" is the
     * whole report, the same as every other scan in the app. A countdown and a
     * "nothing found" verdict were mine, and neither exists anywhere else.
     */
    private fun startWindow() {
        window?.cancel()
        window = scope.launch {
            delay(SCAN_WINDOW_S * 1000L)
            if (_scanning.value) stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        window?.cancel()
        window = null
        val cb = callback ?: return
        callback = null
        _scanning.value = false
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
        var isKnownSensor = false
        rec?.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                val id = sparse.keyAt(i)
                val raw = sparse.valueAt(i) ?: continue
                manu[id] = raw.toHex()
                if (TpmsSignature.isSensor(id, raw, result.device.address)) isKnownSensor = true
                if (decoded == null) {
                    decoded = LyTpmsDecoder.pressureKpa(id, raw, result.device.address)
                }
            }
        }
        val svc = HashMap<String, String>()
        rec?.serviceData?.forEach { (uuid, bytes) -> svc[uuid.uuid.toString()] = bytes.toHex() }
        // Advertised service UUIDs, which carry no payload of their own but
        // are how some sensor families announce themselves. The best known
        // cheap TPMS advertises service 0x27A5 with an empty payload and the
        // short name "BR", and this scan used to drop it on the floor: an
        // advertisement with no manufacturer and no service DATA was thrown
        // away before anything looked at it. A hunt for an undecoded sensor
        // cannot afford a filter that hides whole shapes of packet.
        val uuids = rec?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val name = rec?.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (manu.isEmpty() && svc.isEmpty() && uuids.isEmpty() && name.isNullOrBlank()) return

        val address = result.device.address
        val now = System.currentTimeMillis()
        val previous = _seen.value.firstOrNull { it.address == address }
        val seen = Seen(
            address = address,
            name = name,
            rssi = result.rssi,
            manufacturer = manu,
            service = svc,
            atMs = now,
            firstSeenMs = previous?.firstSeenMs ?: now,
            // A decoded pressure is proof. The MAC-prefix shape is only a
            // hint, kept because it is how an undecoded sensor still shows up
            // as worth looking at while a new model is being worked out.
            looksLikeSensor = decoded != null ||
                manu.values.any { TpmsSignature.looksLikeSensor(it, address) } ||
                svc.values.any { TpmsSignature.looksLikeSensor(it, address) },
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
            "svc=${svc.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
            "uuids=${uuids.joinToString(",")}"
        // Every packet from a candidate, because that is the decode trail;
        // one line per new address otherwise, so the log stays readable.
        if (seen.looksLikeSensor) {
            DiagnosticsLogger.note(line)
        }
        // Adopted on the family signature: the right company id, the right
        // length, and the MAC written backwards in the last six bytes, all
        // three at once.
        //
        // Not on a decoded pressure, which is what it was gated on before, and
        // which meant a disabled decoder disabled finding sensors along with
        // it. Not on the loose "repeats its own MAC" either, which adopted a
        // stranger's device. A sensor can be positively identified while its
        // reading is still unreadable, and this family's is: no byte in any
        // capture tracks the tyre from 78 psi down to 64.
        if (isKnownSensor) {
            val hadSensor = tpms.pairedAddress.value != null
            if (!hadSensor) tpms.adopt(address)
            // The number goes in when there is one. Until this family's format
            // is worked out there is not, and the row says so rather than
            // showing something invented.
            decoded?.let { tpms.submitPaired(it, address, now) }
            // Found it, so stop looking. Flic ends its scan on pair, and a
            // radio left running after the answer arrived costs battery for
            // nothing. Only on the sensor that was actually adopted: a second
            // cap in the room must not end a scan that has not found the
            // rider's yet.
            if (!hadSensor && tpms.pairedAddress.value == address) stop()
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
         * These caps sleep between broadcasts and some only wake when the
         * wheel moves, so a minute was not long enough to be sure: a scan that
         * ends before the sensor speaks looks exactly like a sensor that is
         * not there. Three minutes is long enough to roll the wheel a little
         * and still short enough that a rider who walked away is not leaving
         * the radio running all evening.
         */
        private const val SCAN_WINDOW_S = 180
    }
}
