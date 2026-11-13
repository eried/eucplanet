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
import com.eried.eucplanet.data.model.GpsIdleOff
import com.eried.eucplanet.data.model.GpsPowerPolicy
import com.eried.eucplanet.data.model.GpsSignalEvent
import com.eried.eucplanet.data.model.GpsTier
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.util.AppForeground
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.sync.eucStatsUploadsOn
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.CsvWriter
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

class TripRepository(
    private val context: Context,
    private val tripDao: TripDao,
    // Source of the rider's known wheels for the trip-tools wheel picker.
    private val wheelProfileDao: com.eried.eucplanet.data.db.WheelProfileDao,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    private val externalGpsRepository: ExternalGpsRepository,
    private val legalLockdown: LegalLockdownController,
    /**
     * Injected as a Lazy because [com.eried.eucplanet.share.ShareSession]
     * takes this repository (it publishes [currentLocation]). A Lazy breaks
     * the construction cycle: the instance is only asked for from a coroutine,
     * once, after both singletons exist. That coroutine runs on [scope], which
     * is Dispatchers.IO, so this never runs inline on the constructing thread;
     * a Lazy alone does not guarantee that, the dispatcher does.
     */
    private val shareSession: Lazy<com.eried.eucplanet.share.ShareSession>
) : RideRecording {
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
    override val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    /** [currentLocation] in the units WheelRepository's history buffers keep. */
    override fun currentFix(): GpsFix? = currentLocation.value?.let { loc ->
        GpsFix(
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null,
            altitudeM = if (loc.hasAltitude()) loc.altitude.toFloat() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
        )
    }

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
    // connected, and the Extra-column pairs it streams. Snapshotting only at stop
    // loses it whenever the rider powers the wheel off to end the ride - see
    // [WheelIdentity] and [TripWheelLog], shared with the iPhone's recorder.
    private val wheelLog = TripWheelLog()

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
    // from recording / navigating / sharing / a visible watch map /
    // wheel-connected / app-visible so it never burns the 1 Hz high-accuracy
    // stream when nothing needs it.
    @Volatile private var gpsStreamRequested = false
    @Volatile private var gpsNavigating = false
    // Live location share is running: friends are watching this rider's dot, so
    // GPS stays at 1 Hz even with the app backgrounded and no wheel connected.
    @Volatile private var gpsSharing = false
    @Volatile private var gpsWatchMapVisible = false
    // A HUD is linked and its carousel has the Map screen, which is drawn from
    // this phone's position.
    @Volatile private var gpsHudMapLinked = false
    @Volatile private var currentGpsTier: GpsTier? = null
    // Pending fully-off after the idle grace (gpsIdleOffDelaySec); cancelled the
    // moment any input changes, since recompute re-decides.
    private val idleOff = GpsIdleOff(scope)
    // GPS signal state machine (drives the "GPS acquired/lost" voice). It runs
    // ONLY while a trip is recording or navigation is active - when GPS is both
    // needed AND the app is kept awake, so a stale stream means a genuine
    // satellite loss rather than OEM doze throttling our background location. It
    // never voices the app's own power management (idle power-down, or re-engage
    // on app open / wheel connect). Announces only a GENUINE satellite loss/regain.
    @Volatile private var gpsTracking = false        // true while recording or navigating
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
        // Joining or leaving a group changes the tier the same way navigation
        // does. Resolved here, off the constructor thread, so the Lazy
        // above never re-enters this repository's own construction.
        scope.launch { shareSession.value.sharing.collect { gpsSharing = it; recomputeGpsTier() } }
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
            // Then read the wheel out of any file that has never been asked.
            // Old rows predate the wheelMetaJson column and restored trips
            // arrived before downloads captured it, so the change-wheel picker
            // sat empty while every CSV named its wheel. One streaming read
            // per file, once ever: files with no wheel rows are stamped "{}"
            // so they are never opened again. A 352-trip library takes a few
            // seconds in the background; even thousands stay under a minute.
            runCatching { backfillWheelMetaFromFiles() }
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
            if (eucStatsUploadsOn(appSettings, syncManager.riderStoreId.value)) {
                syncManager.enqueueEucStatsUpload()
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

    /** Enter tracking (a recording or navigation just started). Silent: a recent fix stays FIXED,
     *  otherwise we wait for the first fix, which is a power-on and not voiced. */
    private fun startGpsTracking() {
        if (gpsTracking) return
        gpsTracking = true
        acquiringFromLoss = false
        gpsHasFix = System.currentTimeMillis() - lastFixMs < GPS_LOST_MS && _currentLocation.value != null
        if (gpsHasFix) armLossWatchdog()
    }

    /** Leave tracking (no recording or navigation, or GPS off). Silent. */
    private fun stopGpsTracking() {
        gpsTracking = false
        gpsHasFix = false
        acquiringFromLoss = false
        lossJob?.cancel(); lossJob = null
    }

    /**
     * Ensure the demand-driven GPS stream is running. Callers no longer force a
     * power level - the tier is picked by [recomputeGpsTier] from what actually
     * needs a position (recording / navigating / sharing / connected /
     * app-visible), so a
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

    fun setWatchMapVisible(visible: Boolean) {
        gpsWatchMapVisible = visible
        if (visible) startLocationUpdates()
        recomputeGpsTier()
    }

    fun setHudMapLinked(linked: Boolean) {
        if (gpsHudMapLinked == linked) return
        gpsHudMapLinked = linked
        if (linked) startLocationUpdates()
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
            sharing = gpsSharing,
            watchMapVisible = gpsWatchMapVisible,
            hudMapLinked = gpsHudMapLinked,
        )
        // OFF goes through the idle grace (a LOW fix, then released); any
        // other tier cancels a pending off. Shared with the iPhone.
        idleOff.decide(tier, currentGpsTier, gpsIdleOffDelaySec, ::applyTier)
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
        gpsWatchMapVisible = false
        gpsHudMapLinked = false
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
        val settings = runCatching { settingsRepository.get() }.getOrNull()
        val hasFolder = settings?.syncFolderUri != null
        var adopted = 0
        for (f in csvs) {
            if (f.name in known) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val quads = parseTripQuads(text)
            val m = TripCsv.metricsFrom(quads)
            val rows = (text.count { it == '\n' } - 1).coerceAtLeast(0)  // minus header
            tripDao.insert(
                TripRecord(
                    fileName = f.name,
                    startTime = if (m.valid) m.startMs else f.lastModified(),
                    endTime = if (m.valid) m.endMs else null,
                    distanceKm = m.distanceKm,
                    sampleCount = rows,
                    // ALWAYS null. A uuid is this device's claim that it
                    // recorded the ride itself, and it is what makes a trip
                    // eligible for an eucstats upload; adoption is finding a
                    // file, which is not a recording. Minting one here handed
                    // that claim to imported CSVs, files restored from a
                    // backup, and anything a rider dropped in the folder, and
                    // the upload sweep has no age limit, so a whole back
                    // catalogue went to the leaderboard at once. Live
                    // recording mints its own when the row is created (see
                    // startRecording).
                    tripUuid = null,
                    // The file's own name, the way every other road in reads
                    // it. Ten real files adopted on the emulator showed "test
                    // 3" to eucviewer and a date to the phone.
                    customName = runCatching { readTripNameFromCsv(f) }.getOrNull(),
                    // Queued for the folder like a recorded trip. Adopted files
                    // stayed at status 0, which no worker walks, so they never
                    // reached the backup folder until a manual sync.
                    uploadStatus = if (hasFolder) 1 else 0,
                )
            )
            adopted++
            Log.i(TAG, "Adopted orphan trip CSV ${f.name} ($rows rows, ${m.distanceKm} km)")
        }
        if (adopted > 0 && hasFolder && settings != null) syncManager.enqueueTripUpload(settings)
    }

    /**
     * Finalize any trip left with no end time by a previous session that was
     * killed mid-recording (force-close / crash). The CSV on disk holds every
     * flushed row, so we reconstruct end time / distance / sample count from it
     * and UPDATE the existing row in place -- never insert, so a recovered trip
     * can never duplicate. Runs before [adoptOrphanCsvs] so an empty one is
     * dropped (row + file) and can't then be re-adopted. Idempotent: once an
     * end time is set the row no longer matches [TripDao.getUnfinished].
     */
    suspend fun finalizeUnfinishedTrips() = recoverKilledRecordings(
        tripDao.getUnfinished(),
        // Never touch the live recording (none at cold start, but guard).
        liveTripId = _currentTripId.value,
        readCsv = { trip ->
            val file = getTripFile(trip)
            if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        },
        keep = { finalized, rows ->
            tripDao.update(finalized)
            Log.i(TAG, "Finalized killed recording ${finalized.fileName} ($rows rows, ${finalized.distanceKm} km)")
        },
        drop = { trip ->
            // Nothing usable was captured before the kill: drop the zombie row
            // and its empty file so it doesn't linger as a 0 km trip.
            tripDao.delete(trip)
            val file = getTripFile(trip)
            runCatching { if (file.exists()) file.delete() }
            Log.i(TAG, "Dropped empty killed recording ${trip.fileName}")
        },
    )

    // Wall-clock of the last failed recording start (e.g. an unwritable trips
    // directory). Used to throttle the motion-gated auto-record path so it
    // doesn't reattempt on every telemetry packet after a failure.
    @Volatile private var lastStartFailureMs = 0L

    /** Fold whatever the wheel currently reports into the trip's identity; blanks are ignored. */
    private suspend fun captureWheelIdentity() {
        wheelLog.capture(wheelRepository, settingsRepository.currentOrLoad().lastDeviceAddress)
    }

    // Multi-wheel distance: the wheel's session trip counter is only
    // meaningful within one connection. Accumulate its deltas per segment,
    // resetting the baseline across disconnects, so a mid-ride wheel
    // switch can't fold two odometers into one bogus number (a P6 -> V14
    // swap once produced a "914.5 km" trip this way).
    private var tripWheelKmAccum = 0f
    private var lastWheelTripKm: Float? = null

    /** Queue any not-yet-written identity pairs of the current connection block. */
    private fun queueWheelIdentityExtras() = wheelLog.queueIdentity()

    /**
     * Flush the accumulated wheel identity onto the current row the first time it
     * becomes known (and again only if it grows). Gated on change, so a whole ride
     * costs about one extra write, not one per tick. This is what makes the identity
     * survive an OOM/force-kill: [finalizeUnfinishedTrips] recovers a killed row from
     * its CSV and [finalizedTripOrNull] copies wheelMetaJson through untouched, so a
     * row that already carries the wheel keeps it. Best-effort - a DB hiccup here must
     * never disturb recording. Targets the row by id, and stopRecording() rewrites the
     * same accumulator value, so the two can never disagree.
     */
    private suspend fun persistWheelIdentityIfChanged() {
        val id = currentTrip?.id ?: return
        val json = wheelLog.jsonIfChanged() ?: return
        runCatching { tripDao.updateWheelMeta(id, json) }
    }

    init {
        // Lockdown can engage long after it was armed, the moment legal mode
        // comes on, so a ride may already be in progress. Finalise and save it
        // rather than leaving a partial trip open behind a locked screen.
        scope.launch {
            legalLockdown.engaged.collect { engaged ->
                if (engaged && _recording.value) stopRecording()
            }
        }
    }

    suspend fun startRecording() {
        // Legal Mode Lockdown stops the recorders. Auto-record reaches this same
        // function, so gating here covers the policy too.
        if (legalLockdown.isEngaged()) return

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
        wheelLog.reset()               // never inherit the previous ride's wheel
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
                        settingsRepository.currentOrLoad().gpsPrioritizeExternal &&
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
                    if (!wasConnected) wheelLog.onReconnect() // reconnect: re-emit the block
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
                    wheelLog.onDisconnect()
                    lastWheelTripKm = null
                }
                wasConnected = wheelConnected
                csvWriter?.writeRow(data, location?.toPhoneLocation(), extSpeed, wheelConnected, wheelLog.nextExtra())
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

        // Wheel distance is the per-segment accumulator: only forward,
        // plausible per-tick steps, baseline reset across disconnects. Zero
        // from it is an ANSWER, not a failure - a recording where the wheel
        // never moved really is zero km.
        //
        // It used to fall through to data.tripDistance, the wheel's own trip
        // meter, which is an absolute counter running since the rider last
        // cleared it and has nothing to do with when recording started. A
        // rider stopped a recording after eight stationary seconds and got
        // 6 km: their earlier riding that day, borrowed. The fallback claimed
        // to be for rides "where the loop never saw a connected tick", but it
        // fired whenever the wheel simply had not moved, which is exactly when
        // the honest answer is zero. GPS is the only other thing that measured
        // THIS ride, so it is the only fallback left.
        val distance = if (tripWheelKmAccum > 0f) tripWheelKmAccum else gpsDistanceKm.toFloat()
        val capturedMock = tripHadMockFix
        // A last look in case the wheel is still connected, then use what the ride
        // accumulated - by now a powered-off wheel reads back as all-nulls, which merge
        // ignores rather than letting it erase what we already know.
        captureWheelIdentity()
        val wheelMeta = wheelLog.identity.toJson()
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
        val willEucstats = eucStatsUploadsOn(appSettings, syncManager.riderStoreId.value)

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
        val willEucstats = eucStatsUploadsOn(appSettings, syncManager.riderStoreId.value)
        // One targeted update: the row read before the grace window is stale by
        // now (a Dropbox pass may have marked it), so only the two statuses go.
        if (willSync || willEucstats) {
            tripDao.markFinalized(trip.id, willSync, willEucstats)
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
        if (eucStatsUploadsOn(appSettings, syncManager.riderStoreId.value)) {
            syncManager.enqueueEucStatsUpload()
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
        // The trip may have been one of the copies that differed.
        syncManager.recountFolderConflictsIfWarned()
    }

    /**
     * The trip tools (section, split, combine, rename, change wheel), shared
     * with the iPhone in [TripTools] so both write the same files and rows.
     * Lazy: the init block's backfill can reach it before this line runs.
     */
    private val tools by lazy {
        TripTools(
            tripDao,
            AndroidTripToolFiles { getTripsDir() },
            profileNames = { wheelProfileDao.allNames() },
            resync = { resyncEditedTrip(it) },
        )
    }

    /** See [TripTools.saveTripSection]. */
    suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long): TripRecord? =
        tools.saveTripSection(source, startMs, endMs)

    /** First trip.name= in the Extra column, the way the sync parser reads it. */
    private fun readTripNameFromCsv(file: File): String? {
        file.bufferedReader().use { reader ->
            val header = reader.readLine() ?: return null
            val idx = header.lowercase().split(",").map { it.trim() }.indexOf("extra")
            if (idx < 0) return null
            while (true) {
                val line = reader.readLine() ?: break
                val cell = line.split(",").getOrNull(idx)?.trim().orEmpty()
                if (cell.startsWith("trip.name=", ignoreCase = true)) {
                    val v = cell.substringAfter('=').trim().take(60)
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return null
    }

    /** See [TripTools.backfillWheelMetaFromFiles]. */
    private suspend fun backfillWheelMetaFromFiles() = tools.backfillWheelMetaFromFiles()

    /** See [TripTools.knownWheels]. */
    suspend fun knownWheels(): List<WheelChoice> = tools.knownWheels()

    /** See [TripTools.changeTripWheel]. */
    suspend fun changeTripWheel(trip: TripRecord, wheel: WheelChoice): Boolean = tools.changeTripWheel(trip, wheel)

    /** See [TripTools.renameTrip]. */
    suspend fun renameTrip(trip: TripRecord, name: String): Boolean = tools.renameTrip(trip, name)

    /** Dropbox backup state for one trip, by file name. */
    suspend fun setDropboxStatusByName(fileName: String, status: Int, at: Long?) =
        tripDao.setDropboxStatusByName(fileName, status, at)

    /** Public door to [resyncEditedTrip], for the rider tapping a failed
     *  backup in the trip list. */
    suspend fun resyncTrip(tripId: Long) = resyncEditedTrip(tripId)

    /**
     * Push a trip whose file was edited in place (rename / change wheel) back to
     * the backup folder and Dropbox. The folder worker only walks uploadStatus
     * 1/3, so the row is re-flagged first; Dropbox re-uploads on a size change.
     * Best-effort and gated on the rider having each destination configured.
     */
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

    /** See [TripTools.splitTrip]. */
    suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>): List<TripRecord> =
        tools.splitTrip(source, boundariesMs)

    /** See [TripTools.combineTrips]. */
    suspend fun combineTrips(sources: List<TripRecord>): TripRecord? = tools.combineTrips(sources)

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
        // Nothing left on the phone to differ from the folder.
        syncManager.recountFolderConflictsIfWarned()
    }

    fun getTripFile(trip: TripRecord): File = File(getTripsDir(), trip.fileName)
}

/** Returns true if the location fix came from a mock provider. */
internal fun isMockLocation(loc: Location): Boolean =
    if (Build.VERSION.SDK_INT >= 31) loc.isMock
    else @Suppress("DEPRECATION") loc.isFromMockProvider

