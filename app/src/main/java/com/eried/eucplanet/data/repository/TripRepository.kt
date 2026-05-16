package com.eried.eucplanet.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.CsvWriter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripDao: TripDao,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    private val externalGpsRepository: ExternalGpsRepository
) {
    companion object {
        private const val TAG = "TripRepo"
        private const val RECORD_INTERVAL_MS = 1000L
        // After stopRecording with sync configured, we hold the trip in a "pending"
        // state for this long so the user has a chance to discard it (e.g., a stray
        // short trip from moving the wheel by hand) before it's enqueued for upload.
        // Without a sync folder there's nothing to defer — the trip just stays in
        // the local DB regardless — so the grace window is skipped entirely.
        private const val FINALIZE_GRACE_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // Id of the TripRecord currently being recorded (null when idle). Used so the trip detail
    // screen can tell whether it is viewing the live-recording trip and animate the marker.
    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    // Id of the trip that just finished recording and is in the discard-grace window.
    // Non-null only while we're waiting out [FINALIZE_GRACE_MS] before triggering sync /
    // voice announcement. Cleared when the trip is finalized or discarded by the user.
    private val _pendingTripId = MutableStateFlow<Long?>(null)
    val pendingTripId: StateFlow<Long?> = _pendingTripId.asStateFlow()

    val allTrips: Flow<List<TripRecord>> = tripDao.observeAll()
    val tripCount: Flow<Int> = tripDao.observeCount()

    private var csvWriter: CsvWriter? = null
    private var currentTrip: TripRecord? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    // The just-stopped trip waiting for grace-period finalization, plus the job
    // running the timer. cancelPendingTrip() cancels the job and deletes the trip.
    private var pendingTrip: TripRecord? = null
    private var pendingFinalizeJob: kotlinx.coroutines.Job? = null

    // GPS-accumulated distance for the active recording. Reset at start, read at stop.
    // Preferred over wheel tripDistance because BLE can drop mid-ride, leaving the wheel
    // counter stale or zero while GPS keeps producing valid fixes.
    private var gpsDistanceKm = 0.0
    private var lastGpsPoint: Location? = null

    private var locationFixCount = 0
    private var locationUpdatesActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            locationFixCount++
            // Log first fix and then every 30th update so we can see GPS is alive without spamming.
            if (locationFixCount == 1 || locationFixCount % 30 == 0) {
                Log.i(
                    TAG,
                    "GPS fix #$locationFixCount lat=${"%.6f".format(loc.latitude)} " +
                            "lon=${"%.6f".format(loc.longitude)} " +
                            "acc=${"%.1f".format(loc.accuracy)}m " +
                            "speed=${if (loc.hasSpeed()) "%.1f m/s".format(loc.speed) else "n/a"}"
                )
            }
            _currentLocation.value = loc
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (locationUpdatesActive) {
            Log.d(TAG, "Location updates already active, skipping")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationUpdatesActive = true
            Log.i(TAG, "Location updates started (PRIORITY_HIGH_ACCURACY, 1Hz)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesActive = false
        Log.i(TAG, "Location updates stopped (received $locationFixCount fixes this session)")
    }

    fun getTripsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun startRecording() {
        // Atomically claim the recording slot. If another caller already flipped
        // _recording to true (e.g. connect + first-motion racing, or a duplicate
        // intent), this returns false and we bail before announcing or opening files.
        if (!_recording.compareAndSet(expect = false, update = true)) return

        // Sanity-check location permission at recording start so missing permission is obvious in logs.
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Starting recording WITHOUT location permission — trip will have no GPS")
        }

        // Ensure location updates are running — may already be started by WheelService,
        // but we also want GPS when recording without a wheel connected.
        startLocationUpdates()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "trip_$dateStr.csv"
        val file = File(getTripsDir(), fileName)

        val writer = CsvWriter(file)
        writer.open()
        csvWriter = writer

        gpsDistanceKm = 0.0
        lastGpsPoint = null

        val trip = TripRecord(fileName = fileName)
        val id = tripDao.insert(trip)
        currentTrip = trip.copy(id = id)
        _currentTripId.value = id

        Log.i(TAG, "Recording started: $fileName")
        scope.launch {
            val s = settingsRepository.get()
            if (s.announceRecording) voiceService.announceEvent(context.getString(R.string.voice_recording_started))
        }

        // Periodic write loop
        recordJob = scope.launch {
            var rowsWritten = 0
            var rowsWithGps = 0
            while (_recording.value) {
                val data = wheelRepository.wheelData.value
                val location = _currentLocation.value
                // Snapshot the external GPS sample only if it's recent — staler than
                // the record interval and we'd be writing a frozen reading. Empty when
                // no device is paired or a sample hasn't arrived yet.
                val extSample = externalGpsRepository.currentSample.value
                val extSpeed = extSample
                    ?.takeIf { System.currentTimeMillis() - it.timestamp < RECORD_INTERVAL_MS * 3 }
                    ?.speedKmh
                csvWriter?.writeRow(data, location, extSpeed)
                rowsWritten++
                if (location != null) {
                    rowsWithGps++
                    val prev = lastGpsPoint
                    // Only accumulate when the fix looks credible: reasonable accuracy and a
                    // meaningful jump. Skip the first meters after acquiring a fix (prev==null)
                    // so cold-start jitter doesn't inflate the total.
                    if (prev != null && location.accuracy <= 25f) {
                        val deltaMeters = prev.distanceTo(location)
                        if (deltaMeters in 0.5f..200f) {
                            gpsDistanceKm += deltaMeters / 1000.0
                        }
                    }
                    lastGpsPoint = location
                }
                if (rowsWritten % 30 == 0) {
                    Log.i(TAG, "Recording: $rowsWritten rows ($rowsWithGps with GPS, ${"%.2f".format(gpsDistanceKm)} km)")
                }
                kotlinx.coroutines.delay(RECORD_INTERVAL_MS)
            }
            Log.i(TAG, "Recorder loop ending: $rowsWritten rows, $rowsWithGps with GPS")
        }
    }

    suspend fun stopRecording() {
        // Atomically release the recording slot so a second stop caller cannot
        // double-cleanup or double-announce the stop.
        if (!_recording.compareAndSet(expect = true, update = false)) return
        recordJob?.cancel()
        recordJob = null

        csvWriter?.close()
        csvWriter = null

        // If a previous stop is still in its grace window, finalize it now (the new
        // stop pre-empts the old undo opportunity — only one trip is ever pending).
        finalizePendingTripIfAny()

        val trip = currentTrip
        currentTrip = null
        _currentTripId.value = null
        if (trip == null) {
            Log.i(TAG, "Recording stopped (no current trip to finalize)")
            return
        }

        val data = wheelRepository.wheelData.value
        // GPS-derived distance is preferred; fall back to wheel session counter only if
        // we never accumulated any GPS movement (e.g. recording without location permission).
        val distance = if (gpsDistanceKm > 0.0) gpsDistanceKm.toFloat() else data.tripDistance
        val finishedTrip = trip.copy(
            endTime = System.currentTimeMillis(),
            distanceKm = distance,
            uploadStatus = 0
        )
        // Write endTime/distance immediately so the trip list shows it correctly.
        // uploadStatus stays 0 — we only flip it to 1 (queued for sync) after grace.
        tripDao.update(finishedTrip)

        // Voice announcement fires immediately so the user gets audible feedback
        // that the recording stopped, even if they end up discarding the trip in
        // the grace window. Only the sync upload is gated by the timer.
        val appSettings = settingsRepository.get()
        if (appSettings.announceRecording) {
            scope.launch {
                voiceService.announceEvent(context.getString(R.string.voice_recording_finished))
            }
        }

        // No sync folder = no upload to defer = no grace window. The trip is fully
        // saved locally already (endTime/distance written above); just exit.
        if (appSettings.syncFolderUri == null) {
            Log.i(TAG, "Recording stopped (no sync folder, finalized immediately)")
            return
        }

        pendingTrip = finishedTrip
        _pendingTripId.value = finishedTrip.id
        Log.i(TAG, "Recording stopped, ${FINALIZE_GRACE_MS / 1000}s grace before sync")

        pendingFinalizeJob = scope.launch {
            try {
                kotlinx.coroutines.delay(FINALIZE_GRACE_MS)
                finalizePendingTrip()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by deleteTrip on the pending trip — user discarded it.
            }
        }
    }

    /** Run the deferred finalize step: queue the trip for sync upload. */
    private suspend fun finalizePendingTrip() {
        val trip = pendingTrip ?: return
        val appSettings = settingsRepository.get()
        val willSync = appSettings.syncFolderUri != null
        if (willSync) {
            tripDao.update(trip.copy(uploadStatus = 1))
        }
        pendingTrip = null
        _pendingTripId.value = null
        pendingFinalizeJob = null
        Log.i(TAG, "Trip finalized: ${trip.fileName} (sync=$willSync)")
        if (willSync) syncManager.enqueueTripUpload(appSettings)
    }

    /**
     * If a stop is already in its grace window when a new stop arrives (or the app
     * shuts down cleanly), bring the old pending trip across the finish line instead
     * of leaving it half-saved.
     */
    private suspend fun finalizePendingTripIfAny() {
        if (pendingTrip == null) return
        pendingFinalizeJob?.cancel()
        pendingFinalizeJob = null
        finalizePendingTrip()
    }

    /**
     * Cancel and discard the just-finished trip during the grace window. Equivalent
     * to calling [deleteTrip] on the pending trip — kept as an explicit entry point
     * for screens that want a "just throw it away" call without holding the
     * TripRecord. No-op if no trip is pending.
     */
    suspend fun cancelPendingTrip() {
        val trip = pendingTrip ?: return
        deleteTrip(trip)
        Log.i(TAG, "Pending trip discarded: ${trip.fileName}")
    }

    suspend fun deleteTrip(trip: TripRecord) {
        // If the user is deleting the trip currently in its discard-grace window,
        // cancel the finalize timer so the just-deleted row doesn't sneak through
        // and get queued for sync upload after the fact.
        if (pendingTrip?.id == trip.id) {
            pendingFinalizeJob?.cancel()
            pendingFinalizeJob = null
            pendingTrip = null
            _pendingTripId.value = null
        }
        tripDao.delete(trip)
        val file = File(getTripsDir(), trip.fileName)
        if (file.exists()) file.delete()
    }

    suspend fun insertTrip(trip: TripRecord): Long = tripDao.insert(trip)

    suspend fun updateTrip(trip: TripRecord) = tripDao.update(trip)

    suspend fun getTripById(id: Long): TripRecord? = tripDao.getById(id)

    suspend fun clearAll() {
        if (_recording.value) stopRecording()
        // Drop any pending trip so the user-initiated wipe doesn't leave a
        // ghost finalize job waiting to enqueue a sync upload.
        cancelPendingTrip()
        val dir = getTripsDir()
        // Delete all CSV and DBB files
        dir.listFiles()?.forEach { f ->
            if (f.extension.lowercase() in listOf("csv", "dbb")) f.delete()
        }
        tripDao.deleteAll()
    }

    fun getTripFile(trip: TripRecord): File = File(getTripsDir(), trip.fileName)
}
