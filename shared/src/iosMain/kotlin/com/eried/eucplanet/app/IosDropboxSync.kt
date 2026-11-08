package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.sync.BackgroundOutcome
import com.eried.eucplanet.data.sync.DropboxSyncUi
import com.eried.eucplanet.data.sync.DropboxTripHost
import com.eried.eucplanet.data.sync.DropboxTripSync
import com.eried.eucplanet.data.sync.FolderMirror
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.data.sync.planJsonFolder
import com.eried.eucplanet.data.sync.syncJsonFolder
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
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Dropbox on the iPhone: trips, settings.json (settings and alarm rules) and
 * the saved themes, through the same [DropboxTripSync] passes Android runs.
 *
 * - Sync now is Android's Sync all ([DropboxTripSync.syncAll]): one question
 *   for every real conflict, then the transfers, with the progress bar and
 *   the result the Settings screen shows.
 * - The quiet pass ([DropboxTripSync.backgroundPass]) is Android's
 *   DropboxSyncWorker. Android runs it on WorkManager; the iPhone runs it
 *   in-process: a moment after launch, on every return to the front, when a
 *   trip finishes, every pendingUploadIntervalMin while the app is open, and
 *   on Android's retry curve after a failure. Not while the app is closed
 *   (docs/IOS_PARITY.md).
 * - No backup folder on iOS: the folder hooks report nothing to do, and the
 *   JSON folder is Documents/themes, mirrored with Dropbox's /themes.
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
        // No backup folder on iOS.
        override suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String) = true
        override suspend fun snapshotBeforeRestore() = Unit
        override fun enqueueFolderUpload(settings: AppSettings) = Unit

        override suspend fun countFolderConflicts(settings: AppSettings): Int {
            val local = g.themeFiles.jsonFiles() ?: return 0
            return planJsonFolder(g.dropbox, "themes", local)?.conflicts?.size ?: 0
        }

        override suspend fun syncFolders(settings: AppSettings, choice: SyncChoice): Int = try {
            syncJsonFolder(g.dropbox, "themes", g.themeFiles, choice) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "sync /themes failed: ${e.message}")
            0
        }

        // As Android's background mirror of its backup folder: only what Dropbox lacks.
        override suspend fun mirrorFoldersInBackground(settings: AppSettings): FolderMirror {
            var uploaded = 0
            var failed = false
            try {
                val local = g.themeFiles.jsonFiles() ?: return FolderMirror(0, false)
                val remote = g.dropbox.listFolder("/themes") ?: return FolderMirror(0, false)
                val remoteLower = remote.keys.mapTo(HashSet()) { it.lowercase() }
                for ((name, bytes) in local) {
                    if (name.lowercase() in remoteLower) continue
                    if (g.dropbox.uploadFile("/themes/$name", bytes)) uploaded++ else failed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "mirror /themes failed: ${e.message}")
            }
            return FolderMirror(uploaded, failed)
        }
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

    /** DropboxSyncWorker.doWork: one pass at a time, never beside Sync now. */
    private suspend fun runPass(attempt: Int) {
        if (_running.value) {
            // WorkManager's retry: come back later, further up the curve.
            schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
            return
        }
        if (!trips.passLock.tryLock()) return
        pass = currentCoroutineContext().job
        try {
            val outcome = trips.backgroundPass(
                attempt = attempt,
                isStopped = { pass?.isActive != true },
                foregroundRunning = { _running.value },
            )
            if (outcome == BackgroundOutcome.RETRY) {
                schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "pass failed: ${e.message}")
            schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
        } finally {
            pass = null
            trips.passLock.unlock()
        }
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
