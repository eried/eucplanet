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

    /** Written from the BLE callback thread as well as the main one. */
    @Volatile private var callback: ScanCallback? = null

    /** True while the open scan is a background monitor rather than a search. */
    @Volatile private var monitoring = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: Job? = null

    /**
     * Picks the watching back up when Bluetooth returns.
     *
     * The monitor is not a rider standing on a screen, so it must never throw
     * a system dialog at them; it just fails quietly when the radio is off.
     * That left a paired cap unheard for the rest of the session even after
     * the rider turned Bluetooth on for something else, because nothing was
     * watching for it. The radar, external GPS and wheel scans all listen for
     * this; the tyre monitor was the one that did not.
     */
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(
                android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                android.bluetooth.BluetoothAdapter.ERROR,
            )) {
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF,
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    // The scan is already dead; drop our side of it so the
                    // flags do not claim to be watching.
                    stop()
                    monitoring = false
                }
                android.bluetooth.BluetoothAdapter.STATE_ON ->
                    scope.launch { delay(1_000); startMonitoring() }
            }
        }
    }

    init {
        // Below `scope` on purpose: initialisers run in declaration order, so
        // launching from above it reads a field that does not exist yet.
        //
        // Follow the pairing. A sensor the rider owns is listened to for as
        // long as the app is alive, and the radio goes back the moment they
        // delete it. Nothing else has to remember to ask.
        scope.launch {
            tpms.pairedAddress.collect { address ->
                if (address != null) startMonitoring() else stopMonitoring()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Age the readings. Nothing else ever called refresh(), so a cap that
        // went silent kept its number and its green dot for good: staleness
        // was written, tested, and never actually evaluated after the last
        // packet arrived.
        scope.launch {
            while (true) {
                delay(STALENESS_TICK_MS)
                tpms.refresh()
            }
        }
    }

    /**
     * Listen for an already-paired sensor, indefinitely.
     *
     * Separate from [start], which is the rider pressing Scan to FIND a
     * sensor and rightly gives up after a while. There is nothing to find
     * here, so this uses the low power mode and never times out: it is how the
     * pressure keeps moving once the sensor belongs to the rider.
     */
    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (callback != null || tpms.pairedAddress.value == null) return
        openScan(lowPower = true, asMonitor = true)
    }

    /**
     * End whatever search is running and go back to watching.
     *
     * The one way out of a search. Every path that used to call stop() on its
     * own left a rider with a paired sensor and no radio: adopting a second
     * cap, the Stop button, and the first adoption racing the pairing
     * collector all did it, and all of them permanently.
     */
    @SuppressLint("MissingPermission")
    fun resumeMonitoring() {
        stop()
        monitoring = false
        startMonitoring()
    }

    /**
     * The rider left the screen: end a search, keep watching a paired sensor.
     *
     * A plain stop() here was closing the monitor too, and nothing restarted
     * it, because the pairing had not changed. So the pressure updated only
     * while the settings screen happened to be open and froze the moment the
     * rider walked away from it - which is every moment that matters.
     */
    @SuppressLint("MissingPermission")
    fun endSearch() {
        if (monitoring) return
        resumeMonitoring()
    }

    /** Give the radio back when nothing is paired any more. */
    @SuppressLint("MissingPermission")
    fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        stop()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        monitoring = false
        // A monitor already holds the radio; take it over for the search.
        if (callback != null) stop()
        openScan(lowPower = false, asMonitor = false)
    }

    /**
     * Open a scan. [asMonitor] says which KIND, and everything follows from
     * it.
     *
     * Passed in rather than read off the `monitoring` field, which is the
     * mistake this replaces: the field was assigned after this returned, so a
     * monitor opening a scan still read "false" here, announced itself to the
     * UI as a search and armed the give-up window. The rider opened settings
     * to find it already scanning, and Stop only handed the radio back to a
     * monitor that immediately did it again.
     *
     * Returns true when a scan is now open.
     */
    @SuppressLint("MissingPermission")
    private fun openScan(lowPower: Boolean, asMonitor: Boolean): Boolean {
        if (callback != null) return false
        // The button asks the rider to turn Bluetooth on before it ever gets
        // here, so reaching this with the adapter off means they declined.
        // Nothing to report: they know what they just chose.
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _scanning.value = false
            return false
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { record(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Drop the callback as well. Leaving it set made openScan bail
                // on its own guard forever after, so one refused scan - the OS
                // rejects registrations that come too fast - killed every
                // later one until the app was restarted.
                Log.w(TAG, "TPMS scan failed: $errorCode")
                callback = null
                monitoring = false
                _scanning.value = false
            }
        }
        callback = cb
        // No filter: the sensor is unknown, which is the point. Low latency
        // because these broadcast in short bursts and a slower mode misses
        // most of them.
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
        runCatching { scanner.startScan(null, settings, cb) }
            .onSuccess {
                // Only a search shows as "scanning" and only a search gives
                // up; a monitor is invisible and runs on.
                monitoring = asMonitor
                _scanning.value = !asMonitor
                if (!asMonitor) startWindow()
            }
            .onFailure {
                Log.w(TAG, "TPMS scan could not start", it)
                callback = null
                monitoring = false
                _scanning.value = false
            }
        return callback != null
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
            if (_scanning.value) {
                stop()
                // Hand the radio back to the monitor: the search is over but
                // the rider's sensor still has a pressure worth showing.
                startMonitoring()
            }
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
                    decoded = ZeepinTpmsDecoder.pressureKpa(id, raw)
                        ?: LyTpmsDecoder.pressureKpa(id, raw, result.device.address)
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
            val alreadyKnown = tpms.sensors.value.any { it.address == address }
            // Only a rider who pressed Scan may add a sensor.
            //
            // The monitor runs for as long as the app does, so without this a
            // stranger's cap was adopted the moment it came within range: ride
            // past a car with the same family, or park next to one, and their
            // tyres joined the rider's list without anyone touching anything.
            // The monitor exists to hear the caps that are already theirs.
            if (!alreadyKnown && monitoring) return
            if (!alreadyKnown) tpms.adopt(address)
            // The number goes in when there is one. Until this family's format
            // is worked out there is not, and the row says so rather than
            // showing something invented.
            decoded?.let { tpms.submitPaired(it, address, now) }
            // The same packet carries the air temperature, so it costs nothing
            // to keep and answers "did the tyre lose air or just cool down".
            if (tpms.sensors.value.any { it.address == address }) {
                rec?.manufacturerSpecificData?.let { sparse ->
                    for (i in 0 until sparse.size()) {
                        val t = LyTpmsDecoder.temperatureC(
                            sparse.keyAt(i), sparse.valueAt(i) ?: continue, address
                        )
                        if (t != null) {
                            tpms.submitPairedTemp(address, t)
                            tpms.submitPairedVolts(
                                address,
                                LyTpmsDecoder.batteryVolts(sparse.keyAt(i), sparse.valueAt(i), address)
                            )
                            tpms.submitPairedState(
                                address,
                                LyTpmsDecoder.state(sparse.keyAt(i), sparse.valueAt(i), address)
                            )
                            break
                        }
                    }
                }
            }
            // Found it, so stop looking. Flic ends its scan on pair, and a
            // radio left running after the answer arrived costs battery for
            // nothing. Only on the sensor that was actually adopted: a second
            // cap in the room must not end a scan that has not found the
            // rider's yet.
            // One search adds one sensor, then goes back to watching, the
            // way the Flic pairing screen ends on a pair. A rider fitting caps
            // to three wheels presses Scan three times, which is clearer than
            // a scan that keeps collecting whatever is in the room.
            if (!alreadyKnown) resumeMonitoring()
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

        /** How often readings are re-aged. Cheap, and staleness is in minutes. */
        private const val STALENESS_TICK_MS = 15_000L
    }
}
