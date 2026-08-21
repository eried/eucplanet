package com.eried.eucplanet.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.GpsPowerPolicy
import com.eried.eucplanet.data.model.GpsSignalEvent
import com.eried.eucplanet.data.model.GpsTier
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.util.AppForeground
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    // Source of the rider's known wheels for the trip-tools wheel picker.
    private val wheelProfileDao: com.eried.eucplanet.data.db.WheelProfileDao,
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

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    /**
     * The most recent position at ANY age, for framing a map only.
     *
     * [currentLocation] is deliberately gated on freshness so a recording never
     * starts with a stale fix, which means it stays null whenever the rider has
     * location switched off for battery or privacy, or the GPS cannot get a
     * fix. That left every map with nothing to show. This flow has no age gate,
     * so a map can still open somewhere sensible.
     *
     * Never feed this into a recording, a distance, or a navigation decision.
     * It can be hours old and the rider may be nowhere near it.
     */
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

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

    /** Every trip file this phone holds, for comparing against a backup. */
    suspend fun allTripFileNames(): List<String> =
        tripDao.observeAll().first().map { it.fileName }

    private var csvWriter: CsvWriter? = null
    private var currentTrip: TripRecord? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    // Tracks whether any location fix during the active recording was from a mock provider.
    @Volatile private var tripHadMockFix = false

    // The wheel's identity for the active recording, accumulated while it is still
    // connected. Snapshotting only at stop loses it whenever the rider powers the wheel
    // off to end the ride — see [WheelIdentity].
    private val wheelIdentity = WheelIdentity()

    // Last identity JSON already flushed to the current row, so the mid-ride persist
    // writes only when it actually changes (≈once per ride) rather than every tick.
    private var lastPersistedWheelMeta: String? = null

    // Advanced: phone GPS (fused-location) update interval, mirrored from settings
    // so the non-suspend location-request builder can read it synchronously. A
    // change takes effect the next time location updates (re)start.
    @Volatile private var phoneGpsIntervalMs: Long = 1000L
    // Slow keep-warm interval for the idle (balanced / low-power) GPS tiers.
    @Volatile private var phoneGpsIdleIntervalMs: Long = 10000L
    // Cached so the non-suspend recomputeGpsTier can read it. Seconds of idle
    // before GPS is fully released; 0 = immediately. See GpsPowerPolicy / OFF.
    @Volatile private var gpsIdleOffDelaySec: Int = 30

    // Demand-driven GPS power (GpsPowerPolicy). The stream only runs after a real
    // consumer calls startLocationUpdates(); once running it self-adjusts its tier
    // from recording / navigating / wheel-connected / app-visible so it never
    // burns the 1 Hz high-accuracy stream when nothing needs it.
    @Volatile private var gpsStreamRequested = false
    @Volatile private var gpsNavigating = false
    @Volatile private var currentGpsTier: GpsTier? = null
    // Pending fully-off after the idle grace (gpsIdleOffDelaySec); cancelled the
    // moment any input changes, since recompute re-decides.
    private var idleOffJob: kotlinx.coroutines.Job? = null
    // GPS signal state machine (drives the "GPS acquired/lost" voice). It runs
    // ONLY while a trip is recording or navigation is active - when GPS is both
    // needed AND the app is kept awake, so a stale stream means a genuine
    // satellite loss rather than OEM doze throttling our background location. It
    // never voices the app's own power management (idle power-down, or re-engage
    // on app open / wheel connect). Announces only a GENUINE satellite loss/regain.
    @Volatile private var gpsTracking = false        // true while tier == HIGH
    @Volatile private var gpsHasFix = false          // FIXED (true) vs ACQUIRING (false) while tracking
    @Volatile private var acquiringFromLoss = false  // this ACQUIRING followed a genuine loss, not a power-on
    @Volatile private var lastFixMs = 0L
    private var lossJob: kotlinx.coroutines.Job? = null
    private val GPS_LOST_MS = 10_000L                 // no fresh fix for this long while tracking = signal lost
    private val _gpsSignalEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<GpsSignalEvent>(extraBufferCapacity = 4)
    /** Genuine GPS acquired/lost events; power management is never emitted here.
     *  WheelService speaks these directly. */
    val gpsSignalEvents: kotlinx.coroutines.flow.SharedFlow<GpsSignalEvent> = _gpsSignalEvents

    // Ultra battery saving: freshness gates so a cold GPS-off -> record start
    // never logs the stale last-known position (a fake jump at the track start).
    // The recorded-track age gate is the gpsFixMaxAgeSec setting; these stay
    // internal.
    private val FRESH_SEED_MS = 15_000L    // seed _currentLocation from cache only if newer than this
    private val MAX_PLOT_ACCURACY_M = 50f  // reject fixes worse than this for the recorded track

    init {
        scope.launch {
            settingsRepository.settings.collect {
                phoneGpsIntervalMs = it.phoneGpsIntervalMs.toLong().coerceAtLeast(100L)
                phoneGpsIdleIntervalMs = it.phoneGpsIdleIntervalMs.toLong().coerceAtLeast(1000L)
                gpsIdleOffDelaySec = it.gpsIdleOffDelaySec.coerceAtLeast(0)
                // An interval change re-issues the request at the current tier.
                currentGpsTier = null
                recomputeGpsTier()
            }
        }
        // Re-evaluate the GPS tier whenever any input changes.
        scope.launch { _recording.collect { recomputeGpsTier() } }
        scope.launch { wheelRepository.connectionState.collect { recomputeGpsTier() } }
        scope.launch { AppForeground.isForeground.collect { recomputeGpsTier() } }
    }

    // The just-stopped trip waiting for grace-period finalization, plus the job
    // running the timer. cancelPendingTrip() cancels the job and deletes the trip.
    private var pendingTrip: TripRecord? = null
    private var pendingFinalizeJob: kotlinx.coroutines.Job? = null

    init {
        // First finalize any recording a previous session was killed mid-flight
        // (an endTime=null row plus a CSV full of flushed rows); then adopt CSVs
        // that have no DB row at all. Order matters: finalize drops empty killed
        // rows AND their files, so adopt can't re-add them as duplicates.
        scope.launch {
            runCatching { finalizeUnfinishedTrips() }
            runCatching { adoptOrphanCsvs() }
        }
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
            _lastKnownLocation.value = loc
            onGpsFix()
        }
    }

    // --- GPS signal state machine: voice for genuine satellite loss/regain only ---

    private fun onGpsFix() {
        lastFixMs = System.currentTimeMillis()
        if (gpsTracking && !gpsHasFix) {
            gpsHasFix = true
            // Announce "acquired" only if this fix ends a genuine loss - not the
            // first fix after powering GPS on (that transition is silent).
            if (acquiringFromLoss) {
                acquiringFromLoss = false
                _gpsSignalEvents.tryEmit(GpsSignalEvent.ACQUIRED)
            }
        }
        armLossWatchdog()
    }

    /** (Re)arm the no-fix watchdog. Fires only while tracking: if no fresh fix
     *  arrives within GPS_LOST_MS the signal counts as genuinely lost. */
    private fun armLossWatchdog() {
        lossJob?.cancel(); lossJob = null
        if (!gpsTracking) return
        lossJob = scope.launch {
            delay(GPS_LOST_MS)
            if (gpsTracking && gpsHasFix) {
                gpsHasFix = false
                acquiringFromLoss = true
                _gpsSignalEvents.tryEmit(GpsSignalEvent.LOST)
            }
        }
    }

    /** Enter tracking (tier just became HIGH). Silent: a recent fix stays FIXED,
     *  otherwise we wait for the first fix, which is a power-on and not voiced. */
    private fun startGpsTracking() {
        if (gpsTracking) return
        gpsTracking = true
        acquiringFromLoss = false
        gpsHasFix = System.currentTimeMillis() - lastFixMs < GPS_LOST_MS && _currentLocation.value != null
        if (gpsHasFix) armLossWatchdog()
    }

    /** Leave tracking (tier dropped below HIGH, or GPS off). Silent. */
    private fun stopGpsTracking() {
        gpsTracking = false
        gpsHasFix = false
        acquiringFromLoss = false
        lossJob?.cancel(); lossJob = null
    }

    /**
     * Ensure the demand-driven GPS stream is running. Callers no longer force a
     * power level - the tier is picked by [recomputeGpsTier] from what actually
     * needs a position (recording / navigating / connected / app-visible), so a
     * bare "be ready" call from a UI screen costs a low-power keep-warm fix, not
     * the full 1 Hz stream.
     */
    fun startLocationUpdates() {
        // Refuse early starts before the rider has granted a location
        // permission. FusedLocationProviderClient does NOT throw on
        // requestLocationUpdates when the permission is missing on modern
        // devices -- it just silently registers a queue that delivers no fixes.
        if (!hasLocationPermission()) {
            Log.d(TAG, "startLocationUpdates: no location permission yet, skipping")
            return
        }
        val firstStart = !gpsStreamRequested
        gpsStreamRequested = true
        recomputeGpsTier()
        // Warm the UI's first fix once per stream start (not on every tier flip).
        if (firstStart) warmUpFirstFix()
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * NavigationEngine reports guidance start/stop so GPS runs at 1 Hz while
     * navigating even with no wheel connected.
     */
    fun setNavigating(active: Boolean) {
        gpsNavigating = active
        recomputeGpsTier()
    }

    /** Pick the GPS tier for the current demand and (re)issue the fused request. */
    private fun recomputeGpsTier() {
        if (!gpsStreamRequested || !hasLocationPermission()) return
        val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
        val tier = GpsPowerPolicy.tierFor(
            recording = _recording.value,
            navigating = gpsNavigating,
            connected = connected,
            appVisible = AppForeground.isForeground.value,
        )
        if (tier == GpsTier.OFF) {
            // Already off, or an off-grace already pending: stay put. Do NOT
            // re-bridge or reschedule on every recompute - the wheel's connection
            // state flips during reconnect scans, and re-entering here would
            // thrash GPS on and off (spurious acquired/lost, wasted battery).
            if (currentGpsTier == GpsTier.OFF || idleOffJob != null) return
            if (gpsIdleOffDelaySec <= 0) {
                applyTier(GpsTier.OFF)
            } else {
                // Hold a cheap low-power fix through the grace, then fully off if
                // still idle - so a quick app switch doesn't drop the GPS.
                applyTier(GpsTier.LOW)
                idleOffJob = scope.launch {
                    delay(gpsIdleOffDelaySec * 1000L)
                    applyTier(GpsTier.OFF)
                    idleOffJob = null
                }
            }
        } else {
            // Leaving idle: cancel any pending off and apply the live tier.
            idleOffJob?.cancel(); idleOffJob = null
            applyTier(tier)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyTier(tier: GpsTier) {
        // OFF: release the GPS entirely (ultra battery saving idle state) and
        // drop the last position so the fix indicator reads honestly. The stream
        // re-arms on the next recomputeGpsTier when a higher tier is due.
        if (tier == GpsTier.OFF) {
            if (locationUpdatesActive) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                locationUpdatesActive = false
                Log.i(TAG, "GPS tier=OFF - released (background, disconnected, idle)")
            }
            // Power-down is silent (stop tracking); the indicator still reads
            // "no fix" honestly from the nulled position.
            stopGpsTracking()
            _currentLocation.value = null
            currentGpsTier = GpsTier.OFF
            return
        }
        // The genuine-signal voice runs ONLY while a trip is recording or
        // navigation is active - when GPS staleness truly matters AND the app is
        // kept awake. When GPS is HIGH merely because a wheel is connected in the
        // background, aggressive OEM doze (notably Samsung) throttles fix delivery
        // in ~10-30 s bursts; the loss watchdog would otherwise mis-read those
        // gaps as a stream of "signal lost / acquired". Gate on the real need so
        // that stays silent. The GPS power tier itself is unchanged (dashboard /
        // HUD speed stays live while connected).
        if (_recording.value || gpsNavigating) startGpsTracking() else stopGpsTracking()
        if (tier == currentGpsTier && locationUpdatesActive) return
        val (priority, interval) = when (tier) {
            GpsTier.HIGH -> Priority.PRIORITY_HIGH_ACCURACY to phoneGpsIntervalMs
            GpsTier.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to phoneGpsIdleIntervalMs
            GpsTier.LOW -> Priority.PRIORITY_LOW_POWER to phoneGpsIdleIntervalMs
            GpsTier.OFF -> return
        }
        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        try {
            // Re-registering the same callback replaces its prior request, so a
            // tier change swaps power level without a gap in fixes.
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationUpdatesActive = true
            currentGpsTier = tier
            Log.i(TAG, "GPS tier=$tier priority=$priority interval=${interval}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    /**
     * Two-pronged warm-up so the UI sees a position ASAP on first start:
     *   1. lastLocation reads Play Services' shared cache (instant if Maps / a
     *      weather widget / our previous launch touched GPS recently).
     *   2. getCurrentLocation actively requests one fresh HIGH_ACCURACY fix,
     *      waking the GPS engine independent of the periodic stream.
     * Both write to _currentLocation only if still null, so the fresher fix wins.
     */
    @SuppressLint("MissingPermission")
    private fun warmUpFirstFix() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                // No age gate here: this only ever frames a map. Play Services
                // keeps its cached fix even while the rider has location
                // switched off, which is what lets a map open somewhere sensible
                // instead of on the middle of the Atlantic.
                if (cached != null && _lastKnownLocation.value == null) {
                    _lastKnownLocation.value = cached
                }
                if (cached != null && _currentLocation.value == null &&
                    System.currentTimeMillis() - cached.time < FRESH_SEED_MS) {
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
                if (fresh != null) _lastKnownLocation.value = fresh
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
        } catch (e: Exception) {
            Log.e(TAG, "warm-up location fetch failed", e)
        }
    }

    /** Fully stop GPS (Stop all). Ordinary teardown just lets the tier fall to idle. */
    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesActive = false
        gpsStreamRequested = false
        currentGpsTier = null
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
                    // A derived file (a saved section, a join) must not gain an
                    // upload identity just because the database was rebuilt. The
                    // name is the only marker that survives, which is why
                    // TripDerive puts it there.
                    tripUuid = if (TripDerive.isDerived(f.name)) null
                               else java.util.UUID.randomUUID().toString()
                )
            )
            Log.i(TAG, "Adopted orphan trip CSV ${f.name} ($rows rows, ${m.distanceKm} km)")
        }
    }

    /** Header-driven extraction of (date, lat, lon, mileage) rows for metrics. */
    private fun parseQuadsForMetrics(text: String): List<TripCsv.Quad> = parseTripQuads(text)

    /**
     * Finalize any trip left with no end time by a previous session that was
     * killed mid-recording (force-close / crash). The CSV on disk holds every
     * flushed row, so we reconstruct end time / distance / sample count from it
     * and UPDATE the existing row in place -- never insert, so a recovered trip
     * can never duplicate. Runs before [adoptOrphanCsvs] so an empty one is
     * dropped (row + file) and can't then be re-adopted. Idempotent: once an
     * end time is set the row no longer matches [TripDao.getUnfinished].
     */
    suspend fun finalizeUnfinishedTrips() {
        for (trip in tripDao.getUnfinished()) {
            // Never touch the live recording (none at cold start, but guard).
            if (trip.id == _currentTripId.value) continue
            val file = getTripFile(trip)
            val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
            val rows = text?.let { (it.count { c -> c == '\n' } - 1).coerceAtLeast(0) } ?: 0
            val metrics = text?.let { TripCsv.metricsFrom(parseTripQuads(it)) }
            val finalized = finalizedTripOrNull(trip, metrics, rows)
            if (finalized != null) {
                tripDao.update(finalized)
                Log.i(TAG, "Finalized killed recording ${trip.fileName} ($rows rows, ${finalized.distanceKm} km)")
            } else {
                // Nothing usable was captured before the kill: drop the zombie row
                // and its empty file so it doesn't linger as a 0 km trip.
                tripDao.delete(trip)
                runCatching { if (file.exists()) file.delete() }
                Log.i(TAG, "Dropped empty killed recording ${trip.fileName}")
            }
        }
    }

    // Wall-clock of the last failed recording start (e.g. an unwritable trips
    // directory). Used to throttle the motion-gated auto-record path so it
    // doesn't reattempt on every telemetry packet after a failure.
    @Volatile private var lastStartFailureMs = 0L

    /** Fold whatever the wheel currently reports into [wheelIdentity]; blanks are ignored. */
    private suspend fun captureWheelIdentity() {
        wheelIdentity.merge(
            brand = wheelRepository.connectedBrand.value,
            model = wheelRepository.modelName.value,
            serial = wheelRepository.wheelSerial.value,
            bleMac = settingsRepository.get().lastDeviceAddress,
            bleName = wheelRepository.connectedDeviceName.value,
            firmware = wheelRepository.firmwareVersion.value,
        )
    }

    // Multi-wheel distance: the wheel's session trip counter is only
    // meaningful within one connection. Accumulate its deltas per segment,
    // resetting the baseline across disconnects, so a mid-ride wheel
    // switch can't fold two odometers into one bogus number (a P6 -> V14
    // swap once produced a "914.5 km" trip this way).
    private var tripWheelKmAccum = 0f
    private var lastWheelTripKm: Float? = null

    // CSV Extra-column event queue: one key=value pair leaves per written
    // row, so a multi-key event (the wheel identity block) spills onto
    // consecutive rows exactly like euc.world's extra column does.
    private val pendingExtras = ArrayDeque<String>()
    // Pairs already written to the CSV for the CURRENT connection block, so a
    // late-arriving serial adds one row instead of repeating the whole block,
    // while a reconnect (cleared set) re-emits everything.
    private val emittedExtras = HashSet<String>()

    /** Queue any not-yet-written identity pairs of the current connection block. */
    private fun queueWheelIdentityExtras() {
        for (pair in wheelIdentity.extraPairs()) {
            if (emittedExtras.add(pair)) pendingExtras.addLast(pair)
        }
    }

    /**
     * Flush the accumulated wheel identity onto the current row the first time it
     * becomes known (and again only if it grows) — gated on change, so a whole ride
     * costs about one extra write, not one per tick. This is what makes the identity
     * survive an OOM/force-kill: [finalizeUnfinishedTrips] recovers a killed row from
     * its CSV and [finalizedTripOrNull] copies wheelMetaJson through untouched, so a
     * row that already carries the wheel keeps it. Best-effort — a DB hiccup here must
     * never disturb recording. Targets the row by id, and stopRecording() rewrites the
     * same accumulator value, so the two can never disagree.
     */
    private suspend fun persistWheelIdentityIfChanged() {
        val json = wheelIdentity.toJson() ?: return
        if (json == lastPersistedWheelMeta) return
        val id = currentTrip?.id ?: return
        lastPersistedWheelMeta = json
        runCatching { tripDao.updateWheelMeta(id, json) }
    }

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
        wheelIdentity.clear()          // never inherit the previous ride's wheel
        lastPersistedWheelMeta = null
        pendingExtras.clear()
        emittedExtras.clear()
        tripWheelKmAccum = 0f
        lastWheelTripKm = null
        captureWheelIdentity()
        if (wheelRepository.connectionState.value == com.eried.eucplanet.ble.ConnectionState.CONNECTED) {
            queueWheelIdentityExtras()
        }

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
            val fixMaxAgeMs = settingsRepository.get().gpsFixMaxAgeSec * 1000L
            var rowsWritten = 0
            var rowsWithGps = 0
            var wasConnected = wheelRepository.connectionState.value ==
                com.eried.eucplanet.ble.ConnectionState.CONNECTED
            while (_recording.value) {
                val data = wheelRepository.wheelData.value
                // Gate the recorded point on freshness + accuracy so a cold start
                // (GPS just came back from OFF) logs "no GPS yet" instead of the
                // stale last-known position. Distance already skips the first fix
                // and anything over 25 m; this keeps the plotted track from
                // opening with a fake jump.
                val location = _currentLocation.value?.takeIf {
                    System.currentTimeMillis() - it.time < fixMaxAgeMs &&
                        it.accuracy <= MAX_PLOT_ACCURACY_M
                }
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
                // Keep the wheel's identity current while it is still talking to us; the
                // serial/model often only arrive some seconds into the connection. Flush it
                // to the row as soon as it's known so a later kill still recovers the wheel.
                // Identity also streams into the CSV's Extra column: the full block on
                // every (re)connect, single pairs as late fields arrive, and a
                // wheel.disconnected marker on drop, so a trip that switches wheels
                // mid-ride stays reconstructable from the file alone.
                if (wheelConnected) {
                    if (!wasConnected) emittedExtras.clear() // reconnect: re-emit the block
                    captureWheelIdentity()
                    persistWheelIdentityIfChanged()
                    queueWheelIdentityExtras()
                    // Per-segment wheel distance: only forward, plausible steps
                    // count; the baseline resets across disconnects so another
                    // wheel's counter can't inject a jump.
                    val wtd = data.tripDistance
                    val lastKm = lastWheelTripKm
                    if (lastKm != null && wtd >= lastKm && wtd - lastKm < 1f) {
                        tripWheelKmAccum += wtd - lastKm
                    }
                    lastWheelTripKm = wtd
                } else if (wasConnected) {
                    pendingExtras.addLast("wheel.disconnected=1")
                    lastWheelTripKm = null
                }
                wasConnected = wheelConnected
                csvWriter?.writeRow(data, location, extSpeed, wheelConnected, pendingExtras.removeFirstOrNull())
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
        // Wheel distance is the per-segment accumulator (immune to mid-ride
        // wheel switches and to pre-recording session distance); the raw
        // session counter and GPS distance remain as fallbacks for rides
        // where the loop never saw a connected tick.
        val distance = when {
            tripWheelKmAccum > 0f -> tripWheelKmAccum
            data.tripDistance > 0f -> data.tripDistance
            else -> gpsDistanceKm.toFloat()
        }
        val capturedMock = tripHadMockFix
        // A last look in case the wheel is still connected, then use what the ride
        // accumulated — by now a powered-off wheel reads back as all-nulls, which merge
        // ignores rather than letting it erase what we already know.
        captureWheelIdentity()
        val wheelMeta = wheelIdentity.toJson()
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
            val graceMs = runCatching { settingsRepository.get().tripFinalizeGraceMs.toLong() }
                .getOrDefault(FINALIZE_GRACE_MS)
            Log.i(TAG, "Recording stopped, ${graceMs / 1000}s grace before sync")
            pendingFinalizeJob = scope.launch {
                try {
                    kotlinx.coroutines.delay(graceMs)
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

    /**
     * Write a section of [source] out as a new trip and return its row.
     *
     * Non-destructive: [source] is untouched, so a misjudged range costs the
     * rider nothing but a spare entry they can delete.
     *
     * The new trip gets no `tripUuid`, which is what keeps it off the eucstats
     * leaderboard: upload eligibility is a query requiring a non-null uuid. It
     * still lands in the trips directory, so Dropbox and local backup pick it up
     * like any other file.
     *
     * @param startMs absolute epoch millis, inclusive
     * @param endMs absolute epoch millis, inclusive
     * @return the new trip, or null when the range held no rows
     */
    suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long): TripRecord? {
        val srcFile = getTripFile(source)
        if (!srcFile.exists()) return null
        return saveSectionInternal(source, srcFile, startMs, endMs, index = 0)
    }

    /** Shared by [saveTripSection] and [splitTrip]. [index] > 0 numbers the
     *  piece so a split does not produce several identically named files. */
    private suspend fun saveSectionInternal(
        source: TripRecord,
        srcFile: File,
        startMs: Long,
        endMs: Long,
        index: Int,
    ): TripRecord? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            if (index > 0) "_$index" else ""
        val destName = TripDerive.sectionFileName(source.fileName, stamp)
        val destFile = File(getTripsDir(), destName)
        val rows = runCatching { TripDerive.writeSection(srcFile, destFile, startMs, endMs) }
            .getOrElse { e ->
                Log.e(TAG, "Could not write trip section", e)
                runCatching { destFile.delete() }
                return null
            }
        if (rows <= 0) {
            runCatching { destFile.delete() }
            return null
        }
        val metrics = TripCsv.metricsFrom(readQuads(destFile))
        val record = TripRecord(
            fileName = destName,
            startTime = if (metrics.valid) metrics.startMs else startMs,
            endTime = if (metrics.valid) metrics.endMs else endMs,
            distanceKm = metrics.distanceKm,
            sampleCount = rows,
            // Deliberately null. See the KDoc above and TripDerive.
            tripUuid = null,
            wheelMetaJson = source.wheelMetaJson,
        )
        val id = tripDao.insert(record)
        return record.copy(id = id)
    }

    /** Date, lat, lon and odometer per row, for metric recomputation. */
    private fun readQuads(file: File): List<TripCsv.Quad> {
        val out = ArrayList<TripCsv.Quad>()
        file.bufferedReader().use { reader ->
            val header = reader.readLine()?.lowercase()?.split(",")?.map { it.trim() } ?: return out
            val iLat = TripCsv.Columns.latitude(header)
            val iLon = TripCsv.Columns.longitude(header)
            val iMil = TripCsv.Columns.mileage(header)
            while (true) {
                val line = reader.readLine() ?: break
                val p = line.split(",")
                out.add(
                    TripCsv.Quad(
                        date = p.getOrNull(0).orEmpty(),
                        lat = p.getOrNull(iLat)?.toDoubleOrNull() ?: 0.0,
                        lon = p.getOrNull(iLon)?.toDoubleOrNull() ?: 0.0,
                        mileage = p.getOrNull(iMil)?.toFloatOrNull() ?: 0f,
                    )
                )
            }
        }
        return out
    }

    /**
     * Reassign which wheel a trip was ridden on.
     *
     * Rewrites BOTH the database row and the CSV's Extra column, so the app,
     * a shared file and eucviewer agree. The original CSV is kept alongside as
     * `.bak` until the rewrite succeeds, then replaced atomically-ish, because a
     * half-written trip file would be unrecoverable.
     *
     * This never touches an existing leaderboard entry: the caller warns the
     * rider when [TripRecord.eucstatsStatus] says the ride is already up there.
     *
     * @return true when the row was updated
     */
    /**
     * Every wheel name worth offering in the trip-tools picker.
     *
     * Two sources, because neither is complete on its own. Saved profiles cover
     * wheels this phone has paired with; the trips themselves cover wheels that
     * only ever arrived as an imported CSV, which is exactly the case where the
     * rider is most likely to be correcting a wrong label.
     */
    suspend fun knownWheelNames(): List<String> {
        val fromProfiles = runCatching { wheelProfileDao.allNames() }.getOrDefault(emptyList())
        val fromTrips = runCatching {
            tripDao.allWheelMeta().mapNotNull { json ->
                runCatching { org.json.JSONObject(json).optString("ble_name") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        return (fromProfiles + fromTrips).distinct().sorted()
    }

    suspend fun changeTripWheel(trip: TripRecord, bleName: String, mac: String?): Boolean {
        val file = getTripFile(trip)
        if (file.exists()) {
            val tmp = File(file.parentFile, file.name + ".rewrite")
            val ok = runCatching { TripDerive.rewriteWheelIdentity(file, tmp, bleName, mac) >= 0 }
                .getOrElse { e ->
                    Log.e(TAG, "Wheel rewrite failed for ${trip.fileName}", e)
                    runCatching { tmp.delete() }
                    false
                }
            if (ok && tmp.exists()) {
                val bak = File(file.parentFile, file.name + ".bak")
                runCatching { bak.delete() }
                if (file.renameTo(bak) && tmp.renameTo(file)) {
                    runCatching { bak.delete() }
                } else {
                    // Put things back rather than leaving the rider with neither.
                    runCatching { if (!file.exists()) bak.renameTo(file) }
                    runCatching { tmp.delete() }
                    Log.e(TAG, "Could not swap in the rewritten ${trip.fileName}")
                }
            }
        }
        val meta = org.json.JSONObject().apply {
            trip.wheelMetaJson?.let { existing ->
                runCatching { org.json.JSONObject(existing) }.getOrNull()?.let { old ->
                    old.keys().forEach { k -> put(k, old.get(k)) }
                }
            }
            put("ble_name", bleName)
            mac?.let { put("ble_mac", it.replace(":", "").replace("-", "").uppercase()) }
        }
        tripDao.updateWheelMeta(trip.id, meta.toString())
        resyncEditedTrip(trip.id)
        return true
    }

    /**
     * Give a trip a rider-set name (blank clears it, falling back to the date).
     *
     * The name is written into the CSV Extra column as `trip.name=` so it
     * survives export and a Dropbox round-trip with no sidecar file, and cached
     * on the DB row for a fast list / title. Same atomic .rewrite -> .bak swap
     * as [changeTripWheel] so a mid-write kill can never leave the rider with
     * neither file. Re-syncs afterwards.
     */
    suspend fun renameTrip(trip: TripRecord, name: String): Boolean {
        val clean = name.trim().take(60)
        val file = getTripFile(trip)
        if (file.exists()) {
            val tmp = File(file.parentFile, file.name + ".rewrite")
            val ok = runCatching { TripDerive.rewriteTripName(file, tmp, clean) >= 0 }
                .getOrElse { e ->
                    Log.e(TAG, "Trip name rewrite failed for ${trip.fileName}", e)
                    runCatching { tmp.delete() }
                    false
                }
            if (ok && tmp.exists()) {
                val bak = File(file.parentFile, file.name + ".bak")
                runCatching { bak.delete() }
                if (file.renameTo(bak) && tmp.renameTo(file)) {
                    runCatching { bak.delete() }
                } else {
                    runCatching { if (!file.exists()) bak.renameTo(file) }
                    runCatching { tmp.delete() }
                    Log.e(TAG, "Could not swap in the renamed ${trip.fileName}")
                }
            }
        }
        tripDao.updateCustomName(trip.id, clean.ifBlank { null })
        resyncEditedTrip(trip.id)
        return true
    }

    /**
     * Push a trip whose file was edited in place (rename / change wheel) back to
     * the backup folder and Dropbox. The folder worker only walks uploadStatus
     * 1/3, so the row is re-flagged first; Dropbox re-uploads on a size change.
     * Best-effort and gated on the rider having each destination configured.
     */
    /** Dropbox backup state for one trip, by file name. */
    suspend fun setDropboxStatusByName(fileName: String, status: Int, at: Long?) =
        tripDao.setDropboxStatusByName(fileName, status, at)

    /** Public door to [resyncEditedTrip], for the rider tapping a failed
     *  backup in the trip list. */
    suspend fun resyncTrip(tripId: Long) = resyncEditedTrip(tripId)

    private suspend fun resyncEditedTrip(tripId: Long) {
        val appSettings = settingsRepository.get()
        val hasFolder = appSettings.syncFolderUri != null
        val hasDropbox = appSettings.dropboxAccessToken.isNotBlank()
        if (!hasFolder && !hasDropbox) return
        // Mark both destinations before anything runs, so the row shows the
        // upload starting the moment the rider makes the edit.
        if (hasFolder) runCatching { tripDao.markPendingFolderUpload(tripId) }
        if (hasDropbox) runCatching { tripDao.setDropboxStatus(tripId, 1) }
        // Then upload right now, in-process. WorkManager only backstops the
        // failures: handed the whole job, it parked a rename at "Backing up"
        // for minutes while the rider watched (JobScheduler holding the job on
        // a network constraint it would not call satisfied).
        runCatching { syncManager.pushEditedTripNow(tripId) }
    }

    /**
     * Cut [source] into pieces at [boundariesMs] (absolute epoch millis, each
     * the first sample of a new piece) and save each as its own trip.
     *
     * Non-destructive: [source] is untouched. Pieces carry no `tripUuid`, so
     * they are backed up but never reach the leaderboard.
     *
     * @return the pieces created, empty when nothing could be written
     */
    suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>): List<TripRecord> {
        if (boundariesMs.isEmpty()) return emptyList()
        val srcFile = getTripFile(source)
        if (!srcFile.exists()) return emptyList()
        // Turn the cut points into inclusive ranges covering the whole ride.
        val edges = (listOf(Long.MIN_VALUE) + boundariesMs.sorted()) + listOf(Long.MAX_VALUE)
        val out = ArrayList<TripRecord>()
        for (i in 0 until edges.size - 1) {
            val from = edges[i]
            val to = if (edges[i + 1] == Long.MAX_VALUE) Long.MAX_VALUE else edges[i + 1] - 1
            val piece = saveSectionInternal(source, srcFile, from, to, index = i + 1)
            if (piece != null) out.add(piece)
        }
        // A piece is a new file in the trips folder, which the folder worker
        // and the Dropbox sync would both pick up eventually - but only at
        // their next run. A rename pushes straight away, and a rider who just
        // cut a ride in three has the same expectation.
        out.forEach { resyncEditedTrip(it.id) }
        return out
    }

    /**
     * Merge [sources] into one new trip, oldest first.
     *
     * Non-destructive, and the result carries no `tripUuid` for the same reason
     * a split piece does not: it is a trip the rider assembled, not one the app
     * recorded.
     */
    suspend fun combineTrips(sources: List<TripRecord>): TripRecord? {
        val ordered = sources.sortedBy { it.startTime }
        val files = ordered.map { getTripFile(it) }.filter { it.exists() }
        if (files.size < 2) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destName = TripDerive.sectionFileName(ordered.first().fileName, stamp + "_join")
        val destFile = File(getTripsDir(), destName)
        val rows = runCatching { TripDerive.writeJoined(files, destFile) }.getOrElse { e ->
            Log.e(TAG, "Could not join trips", e)
            runCatching { destFile.delete() }
            return null
        }
        if (rows <= 0) { runCatching { destFile.delete() }; return null }
        val metrics = TripCsv.metricsFrom(readQuads(destFile))
        val record = TripRecord(
            fileName = destName,
            startTime = if (metrics.valid) metrics.startMs else ordered.first().startTime,
            endTime = if (metrics.valid) metrics.endMs else ordered.last().endTime,
            distanceKm = metrics.distanceKm,
            sampleCount = rows,
            tripUuid = null,
            wheelMetaJson = ordered.first().wheelMetaJson,
        )
        return record.copy(id = tripDao.insert(record)).also { resyncEditedTrip(it.id) }
    }

    /**
     * Delete [trips] from this phone, moving their backup copies into the
     * archive first.
     *
     * For a source that an extend or a split has replaced. Its samples now live
     * inside another trip, so keeping it means the same ride is counted twice
     * in any listing, and on the next sync it would come back down to a phone
     * that had already merged it away.
     *
     * @return how many were archived and removed
     */
    suspend fun archiveTrips(trips: List<TripRecord>): Int {
        if (trips.isEmpty()) return 0
        // Only drop the phone's copies once the files are out of the way in
        // the backups, otherwise the next sync hands them straight back and
        // the rider is left doing this again.
        val archived = runCatching { syncManager.archiveTripFiles(trips.map { it.fileName }) }
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

    /** Delete every trip here, archiving the backup copies as it goes. */
    suspend fun archiveAllTrips(): Int = archiveTrips(tripDao.observeAll().first())

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
 * Accumulates the connected wheel's identity over the course of a ride.
 *
 * Model, serial and firmware live in [WheelRepository] StateFlows that are nulled the
 * moment BLE drops, and the normal way to end a ride is to power the wheel off — so
 * reading them once at stop hands back nulls, the upload carries no serial or MAC, and
 * eucstats has nothing to key the wheel on. Such a trip still counts for the rider but
 * reaches no wheel or brand board at all.
 *
 * Merging as the ride runs fixes that: once a field is known it stays known, because a
 * later blank is ignored. A real value still replaces an earlier real value (identity
 * trickles in — the MAC at connect, the serial only once the wheel answers).
 */
class WheelIdentity {
    private val fields = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun merge(
        brand: String? = null,
        model: String? = null,
        serial: String? = null,
        bleMac: String? = null,
        bleName: String? = null,
        firmware: String? = null,
    ) {
        put("brand", brand); put("model", model); put("serial", serial)
        put("ble_mac", bleMac); put("ble_name", bleName); put("firmware", firmware)
    }

    private fun put(key: String, value: String?) {
        if (!value.isNullOrBlank()) fields[key] = value
    }

    fun clear() = fields.clear()

    /**
     * The identity as CSV Extra-column pairs, stable order. MAC is upper
     * case without separators (the ecosystem convention for wheel MACs).
     */
    fun extraPairs(): List<String> {
        val out = ArrayList<String>(6)
        fields["ble_name"]?.let { out.add("wheel.name=$it") }
        fields["ble_mac"]?.let { out.add("wheel.mac=" + it.replace(":", "").replace("-", "").uppercase()) }
        fields["brand"]?.let { out.add("wheel.brand=$it") }
        fields["model"]?.let { out.add("wheel.model=$it") }
        fields["serial"]?.let { out.add("wheel.serial=$it") }
        fields["firmware"]?.let { out.add("wheel.firmware=$it") }
        return out
    }

    /** Same shape and blank-handling as [buildWheelMetaJson] — it delegates to it. */
    fun toJson(): String? = buildWheelMetaJson(
        brand = fields["brand"],
        model = fields["model"],
        serial = fields["serial"],
        bleMac = fields["ble_mac"],
        bleName = fields["ble_name"],
        firmware = fields["firmware"],
    )
}

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

/**
 * Header-driven extraction of (date, lat, lon, mileage) rows from a trip CSV.
 * Top-level so the orphan-adopt and killed-recording finalize share it and it is
 * unit-testable without Room / files.
 */
internal fun parseTripQuads(text: String): List<TripCsv.Quad> {
    val lines = text.split('\n')
    if (lines.size < 2) return emptyList()
    val h = lines[0].split(',').map { it.trim().lowercase(Locale.US) }
    val iDate = TripCsv.Columns.date(h); if (iDate < 0) return emptyList()
    val iLat = TripCsv.Columns.latitude(h); val iLon = TripCsv.Columns.longitude(h)
    val iMile = TripCsv.Columns.mileage(h)
    return lines.asSequence().drop(1).mapNotNull { ln ->
        val c = ln.split(',')
        if (iDate >= c.size || c[iDate].isBlank()) return@mapNotNull null
        fun d(i: Int) = if (i in 0 until c.size) c[i].trim().toDoubleOrNull() ?: 0.0 else 0.0
        fun fl(i: Int) = if (i in 0 until c.size) c[i].trim().toFloatOrNull() ?: 0f else 0f
        TripCsv.Quad(c[iDate].trim(), d(iLat), d(iLon), fl(iMile))
    }.toList()
}

/**
 * The finalized copy of a killed recording, reconstructed from its CSV metrics,
 * or null when nothing usable was captured (empty / header-only / no valid rows)
 * so the caller drops the zombie row. Always a copy of the SAME row (id /
 * fileName / uuid preserved) -- the finalize path UPDATEs, never inserts, so
 * recovery can never produce a duplicate. Pure, for unit tests.
 */
internal fun finalizedTripOrNull(trip: TripRecord, metrics: TripCsv.Metrics?, rows: Int): TripRecord? {
    if (metrics == null || !metrics.valid || rows <= 0) return null
    return trip.copy(
        startTime = metrics.startMs,
        endTime = metrics.endMs,
        distanceKm = metrics.distanceKm,
        sampleCount = rows,
    )
}
