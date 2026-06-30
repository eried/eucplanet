package com.eried.eucplanet.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
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
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.eried.eucplanet.util.TripCsv
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
        // After stopRecording with sync configured, we hold the trip in a "pending"
        // state for this long so the user has a chance to discard it (e.g., a stray
        // short trip from moving the wheel by hand) before it's enqueued for upload.
        // Without a sync folder there's nothing to defer, the trip just stays in
        // the local DB regardless, so the grace window is skipped entirely.
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

    // Live GPS-accumulated trip distance, in km. Mirrors the [gpsDistanceKm]
    // accumulator below so the trip-row label can show the same source of
    // truth the SAVED distance uses at finalize. Without this the UI defaults
    // to wheel-reported tripDistance which freezes on BLE drop and snaps back
    // to 0 when the wheel power-cycles mid-ride.
    private val _liveGpsDistanceKm = MutableStateFlow(0f)
    val liveGpsDistanceKm: StateFlow<Float> = _liveGpsDistanceKm.asStateFlow()

    val allTrips: Flow<List<TripRecord>> = tripDao.observeAll()
    val tripCount: Flow<Int> = tripDao.observeCount()

    private var csvWriter: CsvWriter? = null
    private var currentTrip: TripRecord? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    // Tracks whether any location fix during the active recording was from a mock provider.
    @Volatile private var tripHadMockFix = false

    // Advanced: phone GPS (fused-location) update interval, mirrored from settings
    // so the non-suspend location-request builder can read it synchronously. A
    // change takes effect the next time location updates (re)start.
    @Volatile private var phoneGpsIntervalMs: Long = 1000L

    init {
        scope.launch {
            settingsRepository.settings.collect {
                phoneGpsIntervalMs = it.phoneGpsIntervalMs.toLong().coerceAtLeast(100L)
            }
        }
    }

    // The just-stopped trip waiting for grace-period finalization, plus the job
    // running the timer. cancelPendingTrip() cancels the job and deletes the trip.
    private var pendingTrip: TripRecord? = null
    private var pendingFinalizeJob: kotlinx.coroutines.Job? = null

    init {
        // Recover trip CSVs in the trips dir that have no DB row (e.g. after a
        // DB rebuild, or a CSV dropped in manually). Runs before the upload sweep
        // so recovered trips are eligible for it too.
        scope.launch { runCatching { adoptOrphanCsvs() } }
        // App-start recovery sweep. Both workers also pick up orphaned/failed
        // trips (folder: uploadStatus=3; eucstats: status 0 with UUID, 1, or 3),
        // so this catches anything left behind by a previous session that
        // couldn't finish its upload.
        scope.launch {
            val appSettings = runCatching { settingsRepository.get() }.getOrNull() ?: return@launch
            if (appSettings.syncFolderUri != null) {
                syncManager.enqueueTripUpload(appSettings)
            }
            if (appSettings.onlineUploadEnabled && syncManager.riderStoreId.value != null) {
                syncManager.enqueueEucStatsUpload(appSettings)
            }
            if (appSettings.dropboxAccessToken.isNotBlank()) {
                syncManager.enqueueDropboxSync()
            }
        }
    }

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
            // Track mock-location usage during the active recording.
            if (_recording.value) {
                tripHadMockFix = tripHadMockFix || isMockLocation(loc)
            }
            _currentLocation.value = loc
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        // Refuse early starts before the rider has granted a location
        // permission. FusedLocationProviderClient does NOT throw on
        // requestLocationUpdates when the permission is missing on modern
        // devices -- it just silently registers a queue that delivers no
        // fixes. Combined with the `locationUpdatesActive` dedup below, an
        // early call from NavigationOverlayViewModel (which composes before
        // the permission dialog is even shown) was effectively poisoning
        // the listener: no fixes ever flowed, no later call could re-register.
        val hasLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLoc) {
            Log.d(TAG, "startLocationUpdates: no location permission yet, skipping")
            return
        }
        if (locationUpdatesActive) {
            Log.d(TAG, "Location updates already active, skipping")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, phoneGpsIntervalMs)
            .setMinUpdateIntervalMillis(phoneGpsIntervalMs / 2)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationUpdatesActive = true
            Log.i(TAG, "Location updates started (PRIORITY_HIGH_ACCURACY, 1Hz)")
            // Two-pronged warmup so the UI sees a fix as fast as possible:
            //
            //   1. lastLocation reads Play Services' shared cache. If any app
            //      on the phone (Maps, weather, ride-share, even our own
            //      previous launch) has touched GPS recently, this returns
            //      instantly. On a brand-new install where Play Services has
            //      not yet seen this UID, the cache often comes back null --
            //      so we ALSO kick off:
            //
            //   2. getCurrentLocation actively requests a single fresh fix
            //      using HIGH_ACCURACY. This wakes the GPS engine on its own
            //      schedule (independent of requestLocationUpdates' first
            //      callback), and on a phone where GPS is already warm from
            //      another app it comes back in a second or two -- much
            //      faster than waiting for requestLocationUpdates to deliver
            //      its first periodic fix.
            //
            // Both write to _currentLocation only if it is still null, so
            // whichever finishes first wins and a slow path can never
            // overwrite a fresher fix that landed via the live callback.
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                    if (cached != null && _currentLocation.value == null) {
                        Log.i(
                            TAG,
                            "Seeded from cached last-known fix " +
                                "lat=${"%.6f".format(cached.latitude)} " +
                                "lon=${"%.6f".format(cached.longitude)} " +
                                "acc=${"%.1f".format(cached.accuracy)}m " +
                                "age=${(System.currentTimeMillis() - cached.time)}ms"
                        )
                        _currentLocation.value = cached
                    }
                }
                val cts = CancellationTokenSource()
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, cts.token
                ).addOnSuccessListener { fresh ->
                    if (fresh != null && _currentLocation.value == null) {
                        Log.i(
                            TAG,
                            "Seeded from active getCurrentLocation " +
                                "lat=${"%.6f".format(fresh.latitude)} " +
                                "lon=${"%.6f".format(fresh.longitude)} " +
                                "acc=${"%.1f".format(fresh.accuracy)}m"
                        )
                        _currentLocation.value = fresh
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "lastLocation / getCurrentLocation denied", e)
            }
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

    /**
     * Adopt any `*.csv` in the trips directory that has no matching [TripRecord].
     * This recovers trip history after a DB rebuild (the CSVs survive on disk),
     * and lets a CSV dropped into the folder appear as a replayable trip. Metadata
     * (start/end/distance/sample count) is recomputed from the CSV header-driven,
     * so it works for native + imported (DarknessBot etc.) layouts.
     */
    suspend fun adoptOrphanCsvs() {
        val dir = getTripsDir()
        val csvs = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".csv", ignoreCase = true)
        } ?: emptyArray()
        Log.i(TAG, "adoptOrphanCsvs: dir=${dir.absolutePath} csv=${csvs.size}")
        if (csvs.isEmpty()) return
        val known = tripDao.allFileNames().toHashSet()
        for (f in csvs) {
            if (f.name in known) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val quads = parseQuadsForMetrics(text)
            val m = TripCsv.metricsFrom(quads)
            val rows = (text.count { it == '\n' } - 1).coerceAtLeast(0)  // minus header
            tripDao.insert(
                TripRecord(
                    fileName = f.name,
                    startTime = if (m.valid) m.startMs else f.lastModified(),
                    endTime = if (m.valid) m.endMs else null,
                    distanceKm = m.distanceKm,
                    sampleCount = rows,
                    tripUuid = java.util.UUID.randomUUID().toString()
                )
            )
            Log.i(TAG, "Adopted orphan trip CSV ${f.name} ($rows rows, ${m.distanceKm} km)")
        }
    }

    /** Header-driven extraction of (date, lat, lon, mileage) rows for metrics. */
    private fun parseQuadsForMetrics(text: String): List<TripCsv.Quad> {
        val lines = text.split('\n')
        if (lines.size < 2) return emptyList()
        val h = lines[0].split(',').map { it.trim().lowercase(Locale.US) }
        fun idx(vararg names: String) =
            names.firstNotNullOfOrNull { h.indexOf(it).takeIf { i -> i >= 0 } } ?: -1
        val iDate = idx("date"); if (iDate < 0) return emptyList()
        val iLat = idx("latitude"); val iLon = idx("longitude")
        val iMile = idx("total mileage", "mileage", "distance")
        return lines.asSequence().drop(1).mapNotNull { ln ->
            val c = ln.split(',')
            if (iDate >= c.size || c[iDate].isBlank()) return@mapNotNull null
            fun d(i: Int) = if (i in 0 until c.size) c[i].trim().toDoubleOrNull() ?: 0.0 else 0.0
            fun fl(i: Int) = if (i in 0 until c.size) c[i].trim().toFloatOrNull() ?: 0f else 0f
            TripCsv.Quad(c[iDate].trim(), d(iLat), d(iLon), fl(iMile))
        }.toList()
    }

    // Wall-clock of the last failed recording start (e.g. an unwritable trips
    // directory). Used to throttle the motion-gated auto-record path so it
    // doesn't reattempt on every telemetry packet after a failure.
    @Volatile private var lastStartFailureMs = 0L

    suspend fun startRecording() {
        // Back off briefly after a failed start. evaluateAutoRecordOnTelemetry
        // calls this on every moving packet (~10/s); without this it would spin
        // retrying a doomed file open.
        if (System.currentTimeMillis() - lastStartFailureMs < 10_000L) return

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
            Log.w(TAG, "Starting recording WITHOUT location permission, trip will have no GPS")
        }

        // Ensure location updates are running, may already be started by WheelService,
        // but we also want GPS when recording without a wheel connected.
        startLocationUpdates()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "trip_$dateStr.csv"
        val file = File(getTripsDir(), fileName)

        // Opening the CSV can fail (read-only / bad-permission trips directory,
        // full storage, etc.). This runs from the auto-record path the moment a
        // wheel sends motion, so a thrown exception here would crash the whole
        // app on connect. Fail soft: log, release the slot, and skip recording.
        val writer = CsvWriter(file)
        try {
            writer.open()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open trip file ${file.name}; recording aborted", e)
            lastStartFailureMs = System.currentTimeMillis()
            _recording.value = false   // release the slot claimed above
            return
        }
        csvWriter = writer

        gpsDistanceKm = 0.0
        _liveGpsDistanceKm.value = 0f
        lastGpsPoint = null
        tripHadMockFix = false

        val trip = TripRecord(fileName = fileName, tripUuid = java.util.UUID.randomUUID().toString())
        // Persisting the row can fail (disk full, DB locked/corrupt). If it does,
        // unwind everything we just claimed: otherwise _recording stays true for
        // the rest of the session (no future auto-record can start) and the CSV
        // is orphaned with no DB row. Mirrors the file-open failure path above.
        val id = try {
            tripDao.insert(trip)
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist trip row; recording aborted", e)
            lastStartFailureMs = System.currentTimeMillis()
            runCatching { writer.close() }
            runCatching { file.delete() }
            csvWriter = null
            _recording.value = false
            return
        }
        currentTrip = trip.copy(id = id)
        _currentTripId.value = id

        Log.i(TAG, "Recording started: $fileName")
        scope.launch {
            val s = settingsRepository.get()
            if (s.announceRecording) voiceService.announceEvent(context.getString(R.string.voice_recording_started))
        }

        // Periodic write loop. Cadence is the rider's Advanced "trip recording
        // interval" (independent of the dashboard graph rate and the wheel poll),
        // read once at start; a mid-recording change takes effect next recording.
        recordJob = scope.launch {
            val recordIntervalMs = settingsRepository.get().tripRecordIntervalMs.toLong()
            var rowsWritten = 0
            var rowsWithGps = 0
            while (_recording.value) {
                val data = wheelRepository.wheelData.value
                val location = _currentLocation.value
                // The merged GPS-speed column uses the external box's speed only
                // when the rider prioritises external GPS and the sample is
                // recent (a staler reading would freeze the column); otherwise
                // the writer falls back to the phone's GPS speed.
                val extSample = externalGpsRepository.currentSample.value
                val extSpeed = extSample
                    ?.takeIf {
                        settingsRepository.get().gpsPrioritizeExternal &&
                            System.currentTimeMillis() - it.timestamp < recordIntervalMs * 3
                    }
                    ?.speedKmh
                val wheelConnected = wheelRepository.connectionState.value ==
                    com.eried.eucplanet.ble.ConnectionState.CONNECTED
                csvWriter?.writeRow(data, location, extSpeed, wheelConnected)
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
                            _liveGpsDistanceKm.value = gpsDistanceKm.toFloat()
                        }
                    }
                    lastGpsPoint = location
                }
                if (rowsWritten % 30 == 0) {
                    Log.i(TAG, "Recording: $rowsWritten rows ($rowsWithGps with GPS, ${"%.2f".format(gpsDistanceKm)} km)")
                }
                kotlinx.coroutines.delay(recordIntervalMs)
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

        // Capture row count BEFORE closing and nulling the writer.
        val capturedRowCount = csvWriter?.rows ?: 0
        csvWriter?.close()
        csvWriter = null

        // If a previous stop is still in its grace window, finalize it now (the new
        // stop pre-empts the old undo opportunity, only one trip is ever pending).
        finalizePendingTripIfAny()

        val trip = currentTrip
        currentTrip = null
        _currentTripId.value = null
        if (trip == null) {
            Log.i(TAG, "Recording stopped (no current trip to finalize)")
            return
        }

        val data = wheelRepository.wheelData.value
        // Wheel odometer (session trip counter) is the source of truth for
        // distance; fall back to GPS-derived distance only when the wheel never
        // reported one (GPS-only ride, wheel disconnected before any reading, or
        // a wheel family that doesn't expose trip distance).
        val distance = if (data.tripDistance > 0f) data.tripDistance else gpsDistanceKm.toFloat()
        val capturedMock = tripHadMockFix
        val wheelMeta = buildWheelMetaJson(
            brand = wheelRepository.connectedBrand.value,
            model = wheelRepository.modelName.value,
            serial = wheelRepository.wheelSerial.value,
            bleMac = settingsRepository.get().lastDeviceAddress,
            bleName = wheelRepository.connectedDeviceName.value,
            firmware = wheelRepository.firmwareVersion.value,
        )
        val finishedTrip = trip.copy(
            endTime = System.currentTimeMillis(),
            distanceKm = distance,
            uploadStatus = 0,
            isMockLocation = capturedMock,
            sampleCount = capturedRowCount,
            wheelMetaJson = wheelMeta,
        )
        // Write endTime/distance immediately so the trip list shows it correctly.
        // uploadStatus stays 0, we only flip it to 1 (queued for sync) after grace.
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

        val willSync = appSettings.syncFolderUri != null
        val willEucstats = appSettings.onlineUploadEnabled && syncManager.riderStoreId.value != null

        // No upload destination at all: nothing to defer. Trip is already
        // saved locally above; just exit.
        if (!willSync && !willEucstats) {
            Log.i(TAG, "Recording stopped (no sync, no cloud, finalized immediately)")
            return
        }

        pendingTrip = finishedTrip
        _pendingTripId.value = finishedTrip.id

        if (willSync) {
            // Folder sync gets the discard-grace window so the rider can
            // undo a short / accidental trip before it lands in their cloud
            // folder. Eucstats (if also enabled) gets enqueued at the end
            // of the same grace, so a discarded trip never reaches the
            // online profile either.
            Log.i(TAG, "Recording stopped, ${FINALIZE_GRACE_MS / 1000}s grace before sync")
            pendingFinalizeJob = scope.launch {
                try {
                    kotlinx.coroutines.delay(FINALIZE_GRACE_MS)
                    finalizePendingTrip()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Cancelled by deleteTrip on the pending trip, user discarded it.
                }
            }
        } else {
            // Cloud-only (no folder backup configured). The discard-grace
            // existed for the folder upload undo; without that destination,
            // the grace would just delay the eucstats enqueue for no
            // user-visible benefit AND, more importantly, used to skip
            // finalize entirely, that's how the trip-231 orphan happened
            // (status 0 / 0, no icon at all). Finalize immediately.
            Log.i(TAG, "Recording stopped (cloud-only, finalized immediately)")
            scope.launch { finalizePendingTrip() }
        }
    }

    /** Run the deferred finalize step: queue the trip for sync upload. */
    private suspend fun finalizePendingTrip() {
        val trip = pendingTrip ?: return
        val appSettings = settingsRepository.get()
        val willSync = appSettings.syncFolderUri != null
        val willEucstats = appSettings.onlineUploadEnabled && syncManager.riderStoreId.value != null
        // Single update so the folder-sync and eucstats statuses can't clobber
        // each other (both branch from the same `trip` snapshot).
        if (willSync || willEucstats) {
            tripDao.update(mergeFinalizeStatuses(trip, willSync, willEucstats))
        }
        pendingTrip = null
        _pendingTripId.value = null
        pendingFinalizeJob = null
        Log.i(TAG, "Trip finalized: ${trip.fileName} (sync=$willSync, eucstats=$willEucstats)")
        if (willSync) syncManager.enqueueTripUpload(appSettings)
        // Eucstats: enqueue ANY time the rider has it on, not only when this
        // specific trip needs uploading. The worker walks every trip eligible
        // for upload (pending=1 / failed=3 / orphaned=0), so this is also the
        // automatic retry path: a trip that failed last ride gets one more
        // shot the next time the rider finishes a ride.
        if (appSettings.onlineUploadEnabled && syncManager.riderStoreId.value != null) {
            syncManager.enqueueEucStatsUpload(appSettings)
            Log.i(TAG, "Eucstats upload enqueued (incl. retry sweep for prior failures)")
        }
        // Dropbox mirrors the trip too if the rider has it linked. Runs
        // in parallel to the folder + eucstats workers under its own
        // unique-work name so failures retry independently.
        if (appSettings.dropboxAccessToken.isNotBlank()) {
            syncManager.enqueueDropboxSync()
        }
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
     * to calling [deleteTrip] on the pending trip, kept as an explicit entry point
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

/** Returns true if the location fix came from a mock provider. */
internal fun isMockLocation(loc: Location): Boolean =
    if (Build.VERSION.SDK_INT >= 31) loc.isMock
    else @Suppress("DEPRECATION") loc.isFromMockProvider

/**
 * Builds a JSON object string with the connected wheel's metadata.
 * Returns null if ALL fields are null or blank (nothing to record).
 * Only non-null, non-blank values are included in the JSON.
 */
fun buildWheelMetaJson(
    brand: String?,
    model: String?,
    serial: String?,
    bleMac: String?,
    bleName: String?,
    firmware: String?,
): String? {
    val obj = org.json.JSONObject()
    if (!brand.isNullOrBlank())    obj.put("brand", brand)
    if (!model.isNullOrBlank())    obj.put("model", model)
    if (!serial.isNullOrBlank())   obj.put("serial", serial)
    if (!bleMac.isNullOrBlank())   obj.put("ble_mac", bleMac)
    if (!bleName.isNullOrBlank())  obj.put("ble_name", bleName)
    if (!firmware.isNullOrBlank()) obj.put("firmware", firmware)
    return if (obj.length() == 0) null else obj.toString()
}

/**
 * Apply the finalize-time upload statuses in a SINGLE copy so the folder-sync
 * status ([TripRecord.uploadStatus]) and the eucstats status
 * ([TripRecord.eucstatsStatus]) never clobber each other. Each is set to 1
 * ("pending") only when its destination is enabled; otherwise it is left as-is.
 */
fun mergeFinalizeStatuses(trip: TripRecord, willSync: Boolean, willEucstats: Boolean): TripRecord =
    trip.copy(
        uploadStatus = if (willSync) 1 else trip.uploadStatus,
        eucstatsStatus = if (willEucstats) 1 else trip.eucstatsStatus,
    )
