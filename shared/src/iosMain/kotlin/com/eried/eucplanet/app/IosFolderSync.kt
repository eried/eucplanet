package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.sync.BackupFolder
import com.eried.eucplanet.data.sync.BackupFolderHost
import com.eried.eucplanet.data.sync.BackupFolderSync
import com.eried.eucplanet.data.sync.DropboxSyncUi
import com.eried.eucplanet.data.sync.DropboxTripSync
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The backup folder on the iPhone: the shared [BackupFolderSync] Android's
 * SyncManager runs, over a folder picked in the Files app
 * ([IosBackupFolders]), so an Android phone and an iPhone can share one
 * folder (trips/, trips/archive/, the settings backups, the rider id file,
 * themes/ and overlays/).
 *
 * - Sync all ([syncNow]) is SyncManager.startSync: one question for real
 *   conflicts, then the copies, with the progress bar and the result.
 * - The upload pass ([BackupFolderSync.uploadPass]) is Android's
 *   TripUploadWorker. Android runs it on WorkManager; the iPhone runs it
 *   in-process: 10 s after launch, on every return to the front, when a trip
 *   finishes (after the discard grace), every pendingUploadIntervalMin while
 *   open, and on Android's retry curve after a failure. While the app is not
 *   open, iOS runs [passNow] when it chooses (IosBackgroundSync).
 * - With a folder, the saved themes and the studio's presets live in its
 *   themes/ and overlays/, as on Android, and Dropbox syncs those; without
 *   one, in the app's Documents ([jsonRoot]).
 * - The leaderboard rider id stays in Documents (the iPhone needs no folder
 *   for it) and is also written into the folder, so a rider moving between
 *   phones finds their profile there.
 */
internal object IosFolderSync {
    private const val TAG = "IosFolderSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g get() = IosAppGraph

    val sync: BackupFolderSync by lazy {
        BackupFolderSync(
            g.settings, g.database.tripDao(), g.dropbox, g.dropboxSettings, g.alarms,
            IosTripFiles { IosTrips.tripsDir },
            access = { uri -> IosBackupFolders.open(uri) },
            host = object : BackupFolderHost {
                override fun scheduleUpload(attempt: Int) =
                    schedule(attempt, DropboxTripSync.delayForAttempt(attempt))
                override fun cancelUpload() = cancelQueued()
            },
        )
    }

    /** Where the themes/ and overlays/ live: the folder when one is set, else Documents. */
    suspend fun jsonRoot(): BackupFolder = jsonRoot(g.settings.get())

    fun jsonRoot(settings: AppSettings): BackupFolder = sync.folder(settings) ?: IosBackupFolders.documents

    // --- picking and removing the folder -----------------------------------

    /**
     * SyncManager.setSyncFolder: the picker made the bookmark, so save it
     * and count its conflicts. Then what the iPhone keeps in Documents goes
     * into the folder where it is missing there: the saved themes and
     * presets (the folder holds them from now on, as on Android) and the
     * rider id. Nothing the folder already has is overwritten.
     */
    suspend fun setFolder(uri: String): Boolean {
        val folder = IosBackupFolders.open(uri) ?: return false
        sync.useFolder(uri)
        withContext(Dispatchers.IO) { copyMissing(IosBackupFolders.documents, folder) }
        IosEucStats.readRiderId()?.let { sync.ensureRiderIdFile(it) }
        sync.enqueueUpload(g.settings.get())
        Log.i(TAG, "Backup folder set: ${folder.displayName}")
        return true
    }

    /**
     * SyncManager.clearSyncFolder. The themes and presets made while the
     * folder was set come back to Documents (where missing), so they stay on
     * the phone. The leaderboard needs no folder here: uploads carry on.
     */
    suspend fun clearFolder() {
        val folder = sync.folder(g.settings.get())
        if (folder != null) withContext(Dispatchers.IO) { copyMissing(folder, IosBackupFolders.documents) }
        sync.forgetFolder(riderIdInFolder = false)
        Log.i(TAG, "Backup folder removed")
    }

    /** The .json files of [from]'s themes/ and overlays/ that [to] lacks, copied over. */
    private fun copyMissing(from: BackupFolder, to: BackupFolder) {
        for (sub in BackupFolderSync.JSON_SUBFOLDERS) {
            val have = to.list(sub)?.mapTo(HashSet()) { it.name.lowercase() } ?: emptySet()
            val files = from.list(sub).orEmpty()
                .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) && it.name.lowercase() !in have }
            if (files.isEmpty() || !to.ensureDirectory(sub)) continue
            for (f in files) {
                val bytes = from.read("$sub/${f.name}") ?: continue
                if (to.write("$sub/${f.name}", bytes)) Log.i(TAG, "Copied $sub/${f.name}")
            }
        }
    }

    // --- Sync all --------------------------------------------------------------

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

    private val ui = object : DropboxSyncUi {
        override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
            val d = CompletableDeferred<SyncChoice>()
            choice = d
            _conflictCount.value = count
            val picked = d.await()
            _conflictCount.value = null
            choice = null
            return picked
        }
        override fun progress(done: Int, total: Int) { _progress.value = done to total }
        override fun result(result: SyncResult) { _result.value = result }
    }

    /** SyncManager.startSync: the rider's Sync all for the folder, never beside Dropbox's. */
    fun syncNow() {
        if (IosDropboxSync.running.value) return
        if (!_running.compareAndSet(expect = false, update = true)) return
        job = scope.launch {
            try {
                sync.runSync(ui)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "sync failed: ${e.message}")
            } finally {
                _progress.value = null
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

    /** SyncManager.cancelActiveSync: the file in flight finishes, then the pass stops. */
    fun cancelActive() {
        if (!_running.value) return
        _cancelling.value = true
        choice?.complete(SyncChoice.CANCEL)
        job?.cancel()
    }

    // --- the upload pass --------------------------------------------------------

    private var queued: Job? = null
    private var pass: Job? = null

    /** Drop the queued pass, as cancelUniqueWork(UPLOAD_WORK_NAME) does. */
    private fun cancelQueued() {
        queued?.cancel()
        queued = null
    }

    private fun schedule(attempt: Int, delaySeconds: Long) {
        queued?.cancel()
        queued = scope.launch {
            delay(delaySeconds * 1000)
            queued = null
            runPass(attempt)
        }
    }

    /** TripUploadWorker.doWork. False when an upload failed and a retry is queued. */
    private suspend fun runPass(attempt: Int): Boolean {
        pass = currentCoroutineContext().job
        return try {
            val ok = sync.uploadPass()
            if (!ok) {
                val next = attempt + 1
                Log.i(TAG, "Scheduling retry attempt $next in ${DropboxTripSync.delayForAttempt(next)}s")
                schedule(next, DropboxTripSync.delayForAttempt(next))
            }
            ok
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "pass failed: ${e.message}")
            schedule(attempt + 1, DropboxTripSync.delayForAttempt(attempt + 1))
            false
        } finally {
            pass = null
        }
    }

    /**
     * One upload pass, awaited: what a background task runs
     * (IosBackgroundSync). True with no folder, or when every trip is in it.
     */
    suspend fun passNow(): Boolean {
        if (g.settings.get().syncFolderUri == null) return true
        return runPass(0)
    }

    private var started = false

    /**
     * At launch, on every return to the front, and every
     * pendingUploadIntervalMin while open: Android's reconcilePendingTripUploads
     * and its periodic worker. Each pass no-ops without a folder.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // As reconcilePendingTripUploads: 10 s in, and a retry chain already queued is kept.
            delay(10_000)
            if (hasFolder() && queued == null && pass == null) schedule(0, 0)
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { if (hasFolder() && queued == null && pass == null) schedule(0, 0) }
        }
        scope.launch {
            g.settings.settings.map { it.pendingUploadIntervalMin }.distinctUntilChanged().collectLatest { minutes ->
                // Android's floor for periodic work is 15 minutes; so is this one.
                val periodMs = minutes.coerceAtLeast(15) * 60_000L
                while (currentCoroutineContext().isActive) {
                    delay(periodMs)
                    if (hasFolder() && queued == null && pass == null) schedule(0, 0)
                }
            }
        }
    }

    private suspend fun hasFolder() = g.settings.get().syncFolderUri != null

    // --- the launch hook ----------------------------------------------------------

    /**
     * EUC_BACKUP_FOLDER=<path>: uses that folder as if picked in the Files
     * app, through the same bookmark, for the simulator, whose picker a
     * script cannot drive. A relative path is under the app's Documents.
     * Debug builds only (docs/IOS_SYNC.md).
     */
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    fun useFolderForHook(path: String) {
        if (!kotlin.native.Platform.isDebugBinary) return
        val abs = if (path.startsWith("/")) path else "${g.documents}/$path"
        scope.launch {
            val uri = IosBackupFolders.bookmarkForPath(abs)
            if (uri == null) {
                Log.w(TAG, "EUC_BACKUP_FOLDER: no bookmark for $abs")
                return@launch
            }
            val ok = setFolder(uri)
            Log.i(TAG, "EUC_BACKUP_FOLDER: ${if (ok) "set" else "failed"} $abs")
        }
    }

    /**
     * EUC_BACKUP_CHECK=<secs>: [secs] after launch, runs every folder path
     * once through the real code and logs each as "EUC_BACKUP_CHECK: ...":
     * the settings backup and a named copy, a restore from that copy, the
     * rider id file, a theme and a preset, a trip up, an Android trip down
     * and an archive. Its files are named selftest. Debug builds only.
     */
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    fun checkForHook(secs: Long) {
        if (!kotlin.native.Platform.isDebugBinary) return
        scope.launch {
            delay(secs * 1000)
            runCatching { check() }.onFailure { Log.w(TAG, "EUC_BACKUP_CHECK: failed", it) }
        }
    }

    private suspend fun check() {
        fun say(what: String) { Log.i(TAG, "EUC_BACKUP_CHECK: $what") }
        val folder = sync.folder(g.settings.get()) ?: run { say("no folder set"); return }
        say("folder ${folder.displayName}")

        // Settings: the backup, a named copy, the list, a restore from the copy.
        val saved = sync.backupSettings()
        val named = sync.backupSettingsAs("selftest", overwrite = true)
        say("backup=$saved named=$named list=" + sync.listSettingsBackups().map { "${it.fileName}@${it.writtenAt}" })
        val grace = g.settings.get().tripFinalizeGraceMs
        g.settings.update { it.copy(advanced = it.advanced.copy(tripFinalizeGraceMs = if (grace == 20_000) 25_000 else 20_000)) }
        val changed = g.settings.get().tripFinalizeGraceMs
        val restored = sync.restoreSettingsFrom(BackupFolderSync.buildBackupFileName("selftest"))
        say("restore=$restored grace before=$grace changed=$changed after=${g.settings.get().tripFinalizeGraceMs}")

        // The rider id: this phone's own when it has one, else a test id written and taken away again.
        val own = IosEucStats.readRiderId()
        if (own != null) {
            say("rider id ensure=${sync.ensureRiderIdFile(own)} read=${sync.readRiderIdFile() == own}")
        } else {
            val wrote = sync.writeRiderIdFile("selftest-rider")
            val read = sync.readRiderIdFile()
            val found = sync.findRestorableRider()?.storeId
            say("rider id wrote=$wrote read=$read restorable=$found deleted=${sync.deleteRiderIdFile()}")
        }

        // A theme and a preset, written where Android's stores write them, and read back.
        val themeSaved = g.themeFiles.saveTheme("Selftest theme", com.eried.eucplanet.ui.theme.BuiltInThemes.dark.colors)
        val themeRead = g.themeFiles.loadTheme("Selftest theme.json") != null
        val presetSaved = g.overlayPresets.savePreset("Selftest preset", com.eried.eucplanet.hud.protocol.OverlayPreset())
        val presetRead = g.overlayPresets.loadPreset("Selftest preset")?.name
        say("theme saved=$themeSaved read=$themeRead in=" + folder.list("themes")?.map { it.name })
        say("preset saved=$presetSaved read=$presetRead in=" + folder.list("overlays")?.map { it.name })

        // A trip recorded here goes up to trips/.
        val dao = g.database.tripDao()
        val csv = "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra\n" +
            "01.06.2026 20:24:31.204,0.00,100.80,31.00,92,12.0,59.911491,10.757933,1234.500,0.0,0.00,0.00,0.000,0.000,0.000,0.00,0.00,wheel.name=Selftest\n" +
            "01.06.2026 20:24:32.204,12.50,100.70,31.00,92,12.0,59.911591,10.757933,1234.510,12.4,3.10,12.00,0.010,0.000,0.000,0.00,0.00,trip.name=Selftest ride\n"
        val upName = "selftest_iphone.csv"
        IosTripFiles { IosTrips.tripsDir }.writeWhole(upName, csv.encodeToByteArray())
        val now = com.eried.eucplanet.platform.System.currentTimeMillis()
        if (dao.findByFileName(upName) == null) {
            dao.insert(com.eried.eucplanet.data.model.TripRecord(
                fileName = upName, startTime = now - 60_000, endTime = now, distanceKm = 0.1f, uploadStatus = 1,
            ))
        }
        val passed = sync.uploadPass()
        say("trip up pass=$passed in trips/=" + (folder.list("trips")?.any { it.name == upName } == true) +
            " status=${dao.findByFileName(upName)?.uploadStatus}")

        // A trip an Android phone left in trips/ comes down with Sync all.
        val downName = "selftest_android.csv"
        folder.ensureDirectory("trips")
        folder.write("trips/$downName", csv.replace("Selftest ride", "From Android").encodeToByteArray())
        sync.runSync(object : DropboxSyncUi {
            override suspend fun askConflict(count: Int, firstSettings: Boolean) = SyncChoice.IGNORE
            override fun progress(done: Int, total: Int) = Unit
            override fun result(result: SyncResult) { say("sync all result=$result") }
        })
        val down = dao.findByFileName(downName)
        say("trip down row=${down != null} name=${down?.customName} file=" +
            phoneFileExists("${IosTrips.tripsDir}/$downName"))

        // Archiving the uploaded trip moves the folder's copy into trips/archive.
        val up = dao.findByFileName(upName)
        val archived = if (up != null) IosTrips.archiveTrips(listOf(up)) else 0
        say("archive=$archived in trips/archive=" + folder.list("trips/archive")?.map { it.name })
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun phoneFileExists(path: String) = platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(path)
}
