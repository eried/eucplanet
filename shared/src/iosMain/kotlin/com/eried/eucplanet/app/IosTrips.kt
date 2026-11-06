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
import com.eried.eucplanet.ui.dashboard.DashboardTrips
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
 * discard grace before a sync and the trip upload. See docs/IOS_PARITY.md.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosTrips : DashboardTrips, SettingsTrips {
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
                        if (m in 0.5..200.0) gpsKm += m / 1000.0
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
        if (IosAppGraph.settings.get().announceRecording) {
            IosAppGraph.voice.announceEvent(IosPhoneDevice.text(Res.string.voice_recording_finished))
        }
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
