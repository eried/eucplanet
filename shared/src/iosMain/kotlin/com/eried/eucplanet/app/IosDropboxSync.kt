package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.sync.BackgroundOutcome
import com.eried.eucplanet.data.sync.DropboxSyncUi
import com.eried.eucplanet.data.sync.DropboxTripHost
import com.eried.eucplanet.data.sync.DropboxTripSync
import com.eried.eucplanet.data.sync.FolderMirror
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Dropbox on the iPhone: trips, settings.json (settings and alarm rules) and
 * the saved themes and studio presets, through the same [DropboxTripSync] passes Android runs.
 *
 * - Sync now is Android's Sync all ([DropboxTripSync.syncAll]): one question
 *   for every real conflict, then the transfers, with the progress bar and
 *   the result the Settings screen shows.
 * - The quiet pass ([DropboxTripSync.backgroundPass]) is Android's
 *   DropboxSyncWorker. Android runs it on WorkManager; the iPhone runs it
 *   in-process: a moment after launch, on every return to the front, when a
 *   trip finishes, every pendingUploadIntervalMin while the app is open, and
 *   on Android's retry curve after a failure. While the app is not open,
 *   iOS runs [passNow] when it chooses (IosBackgroundSync).
 * - The backup folder hooks are the folder's (IosFolderSync): archiving into
 *   its trips/archive, the snapshot before a restore, the folder upload. The
 *   JSON folders are the backup folder's themes/ and overlays/, or
 *   Documents/themes and Documents/overlays while there is none, mirrored
 *   with Dropbox's /themes and /overlays.
 */
object IosDropboxSync {
    private const val TAG = "IosDropboxSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g get() = IosAppGraph

    private var job: Job? = null
    private var choice: CompletableDeferred<SyncChoice>? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<SyncResult?>(null)
    val result: StateFlow<SyncResult?> = _result.asStateFlow()
    fun consumeResult() { _result.value = null }

    /** How many conflicts the question is about, while the rider decides. */
    private val _conflictCount = MutableStateFlow<Int?>(null)
    val conflictCount: StateFlow<Int?> = _conflictCount.asStateFlow()
    private val _conflict = MutableStateFlow(false)
    val conflict: StateFlow<Boolean> = _conflict.asStateFlow()

    private val _firstSettings = MutableStateFlow(false)
    val firstSettings: StateFlow<Boolean> = _firstSettings.asStateFlow()

    // --- the platform side of the shared passes ---------------------------

    private val host = object : DropboxTripHost {
        override fun scheduleRetry(attempt: Int, delaySeconds: Long?) =
            schedule(attempt, delaySeconds ?: DropboxTripSync.delayForAttempt(attempt))
        override fun cancelRetry() = cancelQueued()
        // The backup folder, as SyncManager plugs it in on Android.
        override suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String) =
            IosFolderSync.sync.archiveInBackupFolder(settings, fileName)
        override suspend fun snapshotBeforeRestore() { IosFolderSync.sync.snapshotBeforeRestore() }
        override fun enqueueFolderUpload(settings: AppSettings) = IosFolderSync.sync.enqueueUpload(settings)

        // The themes/ and overlays/ of the backup folder, or of Documents
        // while there is none, through the same passes Android runs.
        override suspend fun countFolderConflicts(settings: AppSettings): Int =
            IosFolderSync.sync.countJsonSubdirConflicts(IosFolderSync.jsonRoot(settings))

        override suspend fun syncFolders(settings: AppSettings, choice: SyncChoice): Int =
            IosFolderSync.sync.syncJsonSubdirs(IosFolderSync.jsonRoot(settings), choice)

        // As Android's background mirror of its backup folder: only what Dropbox lacks.
        override suspend fun mirrorFoldersInBackground(settings: AppSettings): FolderMirror =
            IosFolderSync.sync.mirrorJsonSubdirsMissingOnDropbox(IosFolderSync.jsonRoot(settings))
    }

    private val ui = object : DropboxSyncUi {
        override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
            val d = CompletableDeferred<SyncChoice>()
            choice = d
            _firstSettings.value = firstSettings
            _conflictCount.value = count
            _conflict.value = true
            val picked = d.await()
            _conflict.value = false
            _conflictCount.value = null
            choice = null
            return picked
        }
        override fun progress(done: Int, total: Int) { _progress.value = done to total }
        override fun result(result: SyncResult) { _result.value = result }
    }

    val trips: DropboxTripSync by lazy {
        DropboxTripSync(
            g.settings, g.database.tripDao(), g.dropbox, g.dropboxSettings,
            IosTripFiles { IosTrips.tripsDir }, host,
        )
    }

    // --- Sync now ---------------------------------------------------------

    /** Android's startDropboxSync: the rider's Sync all. */
    fun syncNow() {
        // One Sync all at a time, as Android's shared syncRunning: never beside the folder's.
        if (IosFolderSync.running.value) return
        if (!_running.compareAndSet(expect = false, update = true)) return
        // As on Android: the rider asked for the library, and a watchdog pass
        // sits behind this one in case it stops halfway.
        scope.launch { g.settings.update { it.copy(dropboxPullRequested = true) } }
        schedule(DropboxTripSync.WATCHDOG_ATTEMPT, DropboxTripSync.delayForAttempt(DropboxTripSync.WATCHDOG_ATTEMPT))
        job = scope.launch {
            try {
                trips.syncAll(ui)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "sync failed: ${e.message}")
            } finally {
                _progress.value = null
                _conflict.value = false
                _conflictCount.value = null
                choice = null
                _cancelling.value = false
                _running.value = false
            }
        }
    }

    fun resolve(picked: SyncChoice) {
        choice?.complete(picked)
    }

    /** Android's cancelActiveSync: the file in flight finishes, then the pass stops. */
    fun cancelActive() {
        if (!_running.value) return
        _cancelling.value = true
        choice?.complete(SyncChoice.CANCEL)
        job?.cancel()
        cancelQueued()
        scope.launch { g.settings.update { it.copy(dropboxPullRequested = false) } }
    }

    /** Android's stopDropboxSync: no more retries, and the pending indicator goes. */
    fun stop() {
        cancelQueued()
        cancelActive()
        scope.launch {
            g.settings.update { it.copy(dropboxSyncPending = false, dropboxPendingCount = 0, dropboxSyncTotal = 0) }
        }
    }

    // --- the quiet pass -----------------------------------------------------

    private var queued: Job? = null
    private var pass: Job? = null

    /** Android's enqueueDropboxSync: a quiet pass now, replacing one already queued. */
    fun enqueue() = schedule(0, 0)

    /** Android's requestDropboxPull: bring the library over in the quiet pass. */
    fun requestPull() {
        scope.launch {
            g.settings.update { it.copy(dropboxPullRequested = true) }
            enqueue()
        }
    }

    /** Android's cancelDropboxSync: drop the queued pass and stop a running one. */
    fun cancelQueuedPass() = cancelQueued()

    /** Drop the queued pass and stop a running one, as cancelUniqueWork does. */
    private fun cancelQueued() {
        queued?.cancel()
        queued = null
        pass?.cancel()
    }

    private fun schedule(attempt: Int, delaySeconds: Long) {
        queued?.cancel()
        queued = scope.launch {
            delay(delaySeconds * 1000)
            queued = null
            runPass(attempt)
        }
    }

    /**
     * DropboxSyncWorker.doWork: one pass at a time, never beside Sync now.
     * False when it did not finish and a retry is queued.
     */
    private suspend fun runPass(attempt: Int): Boolean {
        if (_running.value) {
            // WorkManager's retry: come back later, further up the curve.
            schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
            return false
        }
        if (!trips.passLock.tryLock()) return false
        pass = currentCoroutineContext().job
        try {
            val outcome = trips.backgroundPass(
                attempt = attempt,
                isStopped = { pass?.isActive != true },
                foregroundRunning = { _running.value },
            )
            if (outcome == BackgroundOutcome.RETRY) {
                schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
                return false
            }
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "pass failed: ${e.message}")
            schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
            return false
        } finally {
            pass = null
            trips.passLock.unlock()
        }
    }

    /**
     * One quiet pass, awaited: what a background task runs (IosBackgroundSync).
     * A pass already running is waited for instead. False when it did not finish.
     */
    suspend fun passNow(): Boolean {
        if (!linked()) return true
        if (trips.passLock.isLocked) {
            trips.passLock.withLock { }
            return true
        }
        return runPass(0)
    }

    private var started = false

    /**
     * At launch, on every return to the front, and every
     * pendingUploadIntervalMin while open: Android's cold-start reconcile and
     * its periodic worker. Each pass no-ops when Dropbox is not linked.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // As reconcilePendingDropboxSync: a few seconds in, once the link token has loaded.
            delay(10_000)
            if (linked()) enqueue()
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { if (linked()) enqueue() }
        }
        scope.launch {
            g.settings.settings.map { it.pendingUploadIntervalMin }.distinctUntilChanged().collectLatest { minutes ->
                // Android's floor for periodic work is 15 minutes; so is this one.
                val periodMs = minutes.coerceAtLeast(15) * 60_000L
                while (currentCoroutineContext().isActive) {
                    delay(periodMs)
                    if (linked() && queued == null && pass == null) enqueue()
                }
            }
        }
    }

    private suspend fun linked() = g.settings.get().dropboxAccessToken.isNotBlank()

    // --- linking ------------------------------------------------------------

    /** Link, then as Android after the callback: ask for the library and let the quiet pass fetch it. */
    fun link() {
        // The sign-in sheet is UIKit: presented from the main thread.
        CoroutineScope(Dispatchers.Main).launch {
            if (IosDropboxLink.link(g.dropbox)) requestPull()
        }
    }

    fun unlink() {
        stop()
        scope.launch { g.dropbox.unlink() }
    }
}
