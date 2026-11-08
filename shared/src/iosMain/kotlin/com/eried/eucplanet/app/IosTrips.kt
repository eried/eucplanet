package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.formatLocalDateUs
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.voice_recording_finished
import com.eried.eucplanet.resources.voice_recording_started
import com.eried.eucplanet.service.AutoRecordPolicy
import com.eried.eucplanet.data.repository.WheelChoice
import com.eried.eucplanet.ui.dashboard.DashboardTrips
import com.eried.eucplanet.ui.recording.RecordingTrips
import com.eried.eucplanet.ui.settings.SettingsTrips
import com.eried.eucplanet.util.CsvWriter
import com.eried.eucplanet.util.TextSink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.uuid.Uuid
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlinx.cinterop.readBytes
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData

/**
 * Trip recording on iOS: the same CSV (the shared [CsvWriter]) and the same
 * trip row in the shared database as Android's TripRepository, written at the
 * rider's recording interval, with the phone's GPS from [IosLocation].
 *
 * What Android's recorder also does and this one does not yet: the GPS power
 * tiers, the wheel identity in the Extra column, the external GPS box, the
 * discard grace before a sync. A finished trip goes to Dropbox through
 * [IosDropboxSync]. The trip list reads it as [RecordingTrips]; the trip
 * tools (rename, wheel, split, combine, section) are not on iOS yet, so
 * their screens hide them. See docs/IOS_PARITY.md.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosTrips : DashboardTrips, SettingsTrips, RecordingTrips {
    private const val TAG = "Trips"
    // Fixes worse than this are left off the recorded track, as on Android.
    private const val MAX_PLOT_ACCURACY_M = 50f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao get() = IosAppGraph.database.tripDao()
    val tripsDir: String get() = "${IosAppGraph.documents}/trips"

    private val _recording = MutableStateFlow(false)
    override val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _currentTripId = MutableStateFlow<Long?>(null)
    override val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    override val tripCount: Flow<Int> get() = dao.observeCount()
    override val newestTripId: Flow<Long?> get() = dao.observeAll().map { list -> list.maxByOrNull { it.startTime }?.id }
    override val currentFix: StateFlow<PhoneLocation?> get() = IosLocation.fix
    override val lastKnownFix: StateFlow<PhoneLocation?> get() = IosLocation.lastKnown
    override val currentLocation: StateFlow<PhoneLocation?> get() = IosLocation.fix
    override val allTrips: Flow<List<TripRecord>> get() = dao.observeAll()
    // No discard grace on iOS yet: a stopped trip is final at once.
    override val pendingTripId: StateFlow<Long?> = MutableStateFlow(null)
    private val _liveGpsKm = MutableStateFlow(0f)
    override val liveGpsDistanceKm: StateFlow<Float> = _liveGpsKm.asStateFlow()

    private var writer: CsvWriter? = null
    private var trip: TripRecord? = null
    private var loop: Job? = null
    private var gpsKm = 0.0
    private var wheelKm = 0f

    override suspend fun allTripFileNames(): List<String> = dao.observeAll().first().map { it.fileName }

    override suspend fun clearAll() {
        if (_recording.value) return
        val fm = NSFileManager.defaultManager
        dao.observeAll().first().forEach { fm.removeItemAtPath("$tripsDir/${it.fileName}", null) }
        dao.observeAll().first().forEach { dao.delete(it) }
    }

    override suspend fun startRecording() {
        if (!_recording.compareAndSet(expect = false, update = true)) return
        IosLocation.start()
        IosLocation.keepInBackground(true)

        val now = System.currentTimeMillis()
        val fileName = "trip_${formatLocalDateUs("yyyyMMdd_HHmmss", now)}.csv"
        NSFileManager.defaultManager.createDirectoryAtPath(tripsDir, true, null, null)
        val w = CsvWriter(FileSink("$tripsDir/$fileName"))
        try {
            w.open()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open trip file $fileName; recording aborted", e)
            _recording.value = false
            return
        }
        val record = TripRecord(fileName = fileName, tripUuid = Uuid.random().toString())
        val id = try {
            dao.insert(record)
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist trip row; recording aborted", e)
            w.close()
            NSFileManager.defaultManager.removeItemAtPath("$tripsDir/$fileName", null)
            _recording.value = false
            return
        }
        writer = w
        trip = record.copy(id = id)
        _currentTripId.value = id
        gpsKm = 0.0
        _liveGpsKm.value = 0f
        wheelKm = 0f
        Log.i(TAG, "Recording started: $fileName")
        if (IosAppGraph.settings.get().announceRecording) {
            IosAppGraph.voice.announceEvent(IosPhoneDevice.text(Res.string.voice_recording_started))
        }

        loop = scope.launch {
            val s = IosAppGraph.settings.get()
            val intervalMs = s.tripRecordIntervalMs.toLong()
            val fixMaxAgeMs = s.gpsFixMaxAgeSec * 1000L
            var lastFix: PhoneLocation? = null
            var lastWheelTripKm: Float? = null
            val wheel = IosAppGraph.wheel
            while (_recording.value) {
                val data = wheel.wheelData.value
                val connected = wheel.connectionState.value == ConnectionState.CONNECTED
                val fix = IosLocation.fix.value?.takeIf {
                    System.currentTimeMillis() - it.time < fixMaxAgeMs && it.accuracy <= MAX_PLOT_ACCURACY_M
                }
                // Per-segment wheel distance: only forward, plausible steps.
                if (connected) {
                    val wtd = data.tripDistance
                    val last = lastWheelTripKm
                    if (last != null && wtd >= last && wtd - last < 1f) wheelKm += wtd - last
                    lastWheelTripKm = wtd
                } else {
                    lastWheelTripKm = null
                }
                writer?.writeRow(data, fix, wheelConnected = connected)
                if (fix != null) {
                    val prev = lastFix
                    if (prev != null && fix.accuracy <= 25f) {
                        val m = meters(prev, fix)
                        if (m in 0.5..200.0) {
                            gpsKm += m / 1000.0
                            _liveGpsKm.value = gpsKm.toFloat()
                        }
                    }
                    lastFix = fix
                }
                delay(intervalMs)
            }
        }
    }

    override suspend fun stopRecording() {
        if (!_recording.compareAndSet(expect = true, update = false)) return
        loop?.cancel()
        loop = null
        val rows = writer?.rows ?: 0
        writer?.close()
        writer = null
        IosLocation.keepInBackground(false)
        val t = trip ?: return
        trip = null
        _currentTripId.value = null
        // As on Android: the wheel's own per-segment distance, else the GPS's.
        val distance = if (wheelKm > 0f) wheelKm else gpsKm.toFloat()
        dao.update(t.copy(endTime = System.currentTimeMillis(), distanceKm = distance, sampleCount = rows))
        Log.i(TAG, "Recording stopped: ${t.fileName}, $rows rows, $distance km")
        // As Android's cloud-only finalize: no folder here, so no discard grace.
        // The sweep also retries any trip a previous ride left behind.
        if (IosEucStats.uploadsOn(IosAppGraph.settings.get())) {
            dao.markFinalized(t.id, folder = false, eucstats = true)
            IosEucStats.sweep()
        }
        // Dropbox mirrors the trip too when it is linked, as finalizePendingTrip does.
        if (IosAppGraph.settings.get().dropboxAccessToken.isNotBlank()) IosDropboxSync.enqueue()
        if (IosAppGraph.settings.get().announceRecording) {
            IosAppGraph.voice.announceEvent(IosPhoneDevice.text(Res.string.voice_recording_finished))
        }
    }

    // --- RecordingTrips ---

    override fun startLocationUpdates() = IosLocation.start()
    override suspend fun getTripById(id: Long): TripRecord? = dao.getById(id)
    override suspend fun insertTrip(trip: TripRecord): Long = dao.insert(trip)
    override suspend fun updateTrip(trip: TripRecord) = dao.update(trip)

    override suspend fun deleteTrip(trip: TripRecord) {
        if (_currentTripId.value == trip.id) return
        NSFileManager.defaultManager.removeItemAtPath("$tripsDir/${trip.fileName}", null)
        dao.delete(trip)
    }

    /**
     * As TripRepository.archiveTrips: the Dropbox copies move into
     * /trips/archive first, and only the trips that made it are removed here,
     * so the next sync does not hand them straight back.
     */
    override suspend fun archiveTrips(trips: List<TripRecord>): Int {
        if (trips.isEmpty()) return 0
        val archived = runCatching { IosDropboxSync.trips.archiveTripFiles(trips.map { it.fileName }) }
            .getOrDefault(emptySet())
        var done = 0
        for (t in trips) {
            if (t.fileName !in archived) {
                Log.w(TAG, "Could not archive ${t.fileName}, leaving the trip alone")
                continue
            }
            deleteTrip(t)
            done++
        }
        return done
    }

    override suspend fun archiveAllTrips(): Int = archiveTrips(dao.observeAll().first())

    override suspend fun knownWheels(): List<WheelChoice> = emptyList()
    override suspend fun changeTripWheel(trip: TripRecord, wheel: WheelChoice) = Unit
    override suspend fun renameTrip(trip: TripRecord, name: String) = Unit
    override suspend fun resyncTrip(tripId: Long) = Unit
    override suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>): List<TripRecord> = emptyList()
    override suspend fun combineTrips(sources: List<TripRecord>): TripRecord? = null
    override suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long): TripRecord? = null

    override fun <R> readTripLines(trip: TripRecord, block: (Sequence<String>) -> R): R? {
        val text = NSString.stringWithContentsOfFile("$tripsDir/${trip.fileName}", NSUTF8StringEncoding, null)
            ?: return null
        return block(text.lineSequence())
    }

    override fun readTripBytes(trip: TripRecord): ByteArray? {
        val data = NSData.dataWithContentsOfFile("$tripsDir/${trip.fileName}") ?: return null
        val n = data.length.toInt()
        return if (n == 0) ByteArray(0) else data.bytes!!.readBytes(n)
    }

    override fun writeImportedTrip(baseName: String, bytes: ByteArray): String {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(tripsDir, true, null, null)
        var name = "$baseName.csv"
        var counter = 1
        while (fm.fileExistsAtPath("$tripsDir/$name")) {
            name = "${baseName}_$counter.csv"
            counter++
        }
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile("$tripsDir/$name", true)
        return name
    }

    override fun deleteTripFile(fileName: String) {
        NSFileManager.defaultManager.removeItemAtPath("$tripsDir/$fileName", null)
    }

    /** Auto-record, as WheelService runs it on Android: start on motion, stop when idle. */
    fun followWheel() {
        scope.launch {
            var lastMotionAt = 0L
            IosAppGraph.wheel.wheelData.collect { data ->
                val s = IosAppGraph.settings.currentOrLoad()
                val connected = IosAppGraph.wheel.connectionState.value == ConnectionState.CONNECTED
                val moving = connected && AutoRecordPolicy.isMoving(data.speed)
                if (moving) lastMotionAt = System.currentTimeMillis()
                if (AutoRecordPolicy.shouldStart(s, moving, connected, _recording.value)) {
                    startRecording()
                } else if (_recording.value && !moving) {
                    if (lastMotionAt == 0L) lastMotionAt = System.currentTimeMillis()
                    if (AutoRecordPolicy.shouldStop(s, true, System.currentTimeMillis() - lastMotionAt)) {
                        stopRecording()
                    }
                }
            }
        }
    }

    private fun meters(a: PhoneLocation, b: PhoneLocation): Double {
        val r = 6_371_000.0
        val dLat = (b.latitude - a.latitude) * kotlin.math.PI / 180
        val dLon = (b.longitude - a.longitude) * kotlin.math.PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.latitude * kotlin.math.PI / 180) * cos(b.latitude * kotlin.math.PI / 180) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** Lines appended to a file, flushed when the writer asks. */
    @OptIn(ExperimentalForeignApi::class)
    private class FileSink(private val path: String) : TextSink {
        private var handle: NSFileHandle? = null
        private val pending = StringBuilder()

        override fun open() {
            check(NSFileManager.defaultManager.createFileAtPath(path, null, null)) { "cannot create $path" }
            handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("cannot open $path")
        }

        override fun writeLine(line: String) {
            pending.append(line).append('\n')
        }

        override fun flush() {
            val h = handle ?: return
            if (pending.isEmpty()) return
            val bytes = pending.toString().encodeToByteArray()
            pending.clear()
            bytes.usePinned { h.writeData(NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong())) }
        }

        override fun close() {
            flush()
            handle?.closeFile()
            handle = null
        }
    }
}
