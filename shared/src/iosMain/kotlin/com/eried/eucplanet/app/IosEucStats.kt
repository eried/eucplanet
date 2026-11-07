package com.eried.eucplanet.app

import com.eried.eucplanet.data.eucstats.EucStatsApi
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.EucStatsServer
import com.eried.eucplanet.data.eucstats.EucStatsSettingsPort
import com.eried.eucplanet.data.eucstats.RegisterResult
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.data.eucstats.StubAppAttest
import com.eried.eucplanet.data.eucstats.SyncResult
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.sync.eucStatsUploadsOn
import com.eried.eucplanet.data.sync.sweepEucStatsUploads
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.recording.RecordingOnline
import com.eried.eucplanet.ui.settings.OnlineLeaderboard
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDevice

/**
 * The eucstats leaderboard on the iPhone: the shared repository and upload
 * sweep, with what Android keeps elsewhere kept here.
 *
 * - The rider id: Android writes it to the backup folder; the iPhone has no
 *   folder, so it is a file in the app's Documents (backed up with the phone).
 * - The upload trigger: Android has WorkManager; the iPhone sweeps when a trip
 *   finishes, when the app starts and when it comes back to the front, and
 *   retries in-process on Android's backoff curve while it stays open.
 * - Attestation: StubAppAttest until App Attest is wired on both ends.
 */
internal object IosEucStats {
    private const val TAG = "IosEucStats"
    private const val RIDER_FILE = "eucstats_riderid.txt"

    // SyncManager's retry curve: 15 s doubling, at most 5 min.
    private const val BACKOFF_BASE_SECONDS = 15L
    private const val BACKOFF_MAX_SECONDS = 300L

    /**
     * The API base: production, unless a dev build says otherwise, from the
     * launch environment (SIMCTL_CHILD_EUCSTATS_API_BASE_URL=...) or the
     * Info.plist key EucStatsApiBaseUrl.
     */
    val baseUrl: String by lazy {
        val env = NSProcessInfo.processInfo.environment["EUCSTATS_API_BASE_URL"] as? String
        val plist = NSBundle.mainBundle.objectForInfoDictionaryKey("EucStatsApiBaseUrl") as? String
        (env ?: plist)?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: EucStatsServer.DEFAULT_API_BASE_URL
    }

    private val riderPath get() = "${IosAppGraph.documents}/$RIDER_FILE"

    private val _riderStoreId = MutableStateFlow(readRiderId())
    val riderStoreId: StateFlow<String?> = _riderStoreId.asStateFlow()

    @OptIn(ExperimentalForeignApi::class)
    fun readRiderId(): String? =
        NSString.stringWithContentsOfFile(riderPath, NSUTF8StringEncoding, null)
            ?.trim()?.takeIf { it.isNotBlank() }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun writeRiderId(storeId: String): Boolean {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = (NSString.create(string = storeId)).writeToFile(riderPath, true, NSUTF8StringEncoding, null)
        if (ok) _riderStoreId.value = storeId
        return ok
    }

    @OptIn(ExperimentalForeignApi::class)
    fun deleteRiderId(): Boolean {
        val deleted = NSFileManager.defaultManager.removeItemAtPath(riderPath, null)
        _riderStoreId.value = null
        return deleted
    }

    private val appVersion: String =
        NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "?"

    val repository: EucStatsRepository by lazy {
        EucStatsRepository(
            api = EucStatsApi(HttpClient()) { baseUrl },
            attestation = StubAppAttest(),
            settings = object : EucStatsSettingsPort {
                override suspend fun get(): AppSettings = IosAppGraph.settings.get()
                override suspend fun update(settings: AppSettings) = IosAppGraph.settings.update(settings)
                override fun riderStoreId(): String? = _riderStoreId.value
                override suspend fun writeRiderId(storeId: String) = this@IosEucStats.writeRiderId(storeId)
                override suspend fun deleteRiderId() = this@IosEucStats.deleteRiderId()
            },
            tripDao = IosAppGraph.database.tripDao(),
            tripFileBytes = { trip -> IosTrips.readTripBytes(trip) ?: ByteArray(0) },
            appVersion = appVersion,
            osVersion = "iOS ${UIDevice.currentDevice.systemVersion}",
            platform = "apple",
        )
    }

    /** The finished-trip rule Android's TripRepository uses. */
    fun uploadsOn(settings: AppSettings) = eucStatsUploadsOn(settings, _riderStoreId.value)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweepJob: Job? = null

    /**
     * One sweep and its retry chain, replacing any chain already waiting, as
     * Android's unique work with REPLACE does.
     */
    fun sweep() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            var attempt = 0
            while (true) {
                val result = runCatching {
                    sweepEucStatsUploads(
                        settings = IosAppGraph.settings.get(),
                        riderStoreId = { _riderStoreId.value ?: readRiderId() },
                        tripDao = IosAppGraph.database.tripDao(),
                        eucStatsRepository = repository,
                        tag = TAG,
                    )
                }.onFailure { Log.w(TAG, "Sweep failed", it) }.getOrNull()
                if (result?.retry != true) break
                attempt++
                val secs = (BACKOFF_BASE_SECONDS * (1L shl (attempt - 1).coerceAtMost(20)))
                    .coerceAtMost(BACKOFF_MAX_SECONDS)
                Log.i(TAG, "Scheduling retry attempt $attempt in ${secs}s")
                delay(secs * 1000)
            }
        }
    }

    fun cancel() {
        sweepJob?.cancel()
        sweepJob = null
    }

    private var started = false

    /** At launch and on every return to the front, as Android's cold-start reconcile. */
    fun start() {
        if (started) return
        started = true
        EucStatsServer.apiBaseUrl = baseUrl
        scope.launch {
            // As Android's reconcile: a moment in, once settings have loaded.
            delay(3_000)
            if (uploadsOn(IosAppGraph.settings.get())) sweep()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { if (uploadsOn(IosAppGraph.settings.get())) sweep() }
        }
    }
}

/** The settings screen's leaderboard, as AndroidOnlineLeaderboard wraps the repository. */
internal object IosOnlineLeaderboard : OnlineLeaderboard {
    private val stats get() = IosEucStats.repository
    override val card: StateFlow<RiderCard?> get() = stats.card
    override val cardLoaded: StateFlow<Boolean> get() = stats.cardLoaded
    override val cardMissing: StateFlow<Boolean> get() = stats.cardMissing
    override suspend fun refreshCard() { stats.refreshCard() }
    override suspend fun register(displayName: String, flag: String, avatarPngBase64: String): RegisterResult =
        stats.register(displayName, flag, avatarPngBase64)
    override suspend fun syncPendingNow(onProgress: (done: Int, total: Int) -> Unit): SyncResult =
        stats.syncPendingNow(onProgress)
    override suspend fun fetchProfile(): RiderProfile? = stats.fetchProfile()
    override suspend fun editProfile(displayName: String?, flag: String?, avatarPngBase64: String?) =
        stats.editProfile(displayName, flag, avatarPngBase64)
    override suspend fun deleteAccount() = stats.deleteAccount()
    override suspend fun exportData(): String? = stats.exportData()
}

/** The trip list's leaderboard status and its Sync all, as AndroidRecordingOnline. */
internal object IosRecordingOnline : RecordingOnline {
    override val riderLinked get() = IosEucStats.riderStoreId.value != null
    override suspend fun syncPendingNow(): SyncResult = IosEucStats.repository.syncPendingNow { _, _ -> }
    override fun enqueueUpload(settings: AppSettings) = IosEucStats.sweep()
    override suspend fun refreshTripVerdict(trip: TripRecord) = IosEucStats.repository.refreshTripVerdict(trip)
}
