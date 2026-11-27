package com.eried.eucplanet.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.eried.eucplanet.util.LocaleHelper
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordinates backup and sync: the SAF backup folder the rider picks (trips,
 * settings.json, themes, overlays, the eucstats rider id file), the Dropbox
 * mirror through [DropboxRepository], and the WorkManager jobs that retry both.
 *
 * The folder is picked in any DocumentsProvider with ACTION_OPEN_DOCUMENT_TREE.
 * We take persistable permission and store the tree URI in settings. What is
 * in the folder and every rule about it is the shared [BackupFolderSync]
 * ([folderSync]), the iPhone's too; this class adds the permission, the
 * progress flows and the WorkManager jobs, and reaches the folder through
 * [SafBackupFolder].
 */
class SyncManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val alarmDao: AlarmDao,
    private val dropboxRepository: DropboxRepository
) {
    companion object {
        private const val TAG = "SyncManager"
        // The folder's names are the shared ones, so an iPhone reads the same folder.
        const val SETTINGS_BACKUP_NAME = BackupFolderSync.SETTINGS_BACKUP_NAME
        const val SETTINGS_BACKUP_PREFIX = BackupFolderSync.SETTINGS_BACKUP_PREFIX
        const val SETTINGS_BACKUP_SUFFIX = BackupFolderSync.SETTINGS_BACKUP_SUFFIX
        /** See [BackupFolderSync.RIDER_BACKUP_NAME]. */
        const val RIDER_BACKUP_NAME = BackupFolderSync.RIDER_BACKUP_NAME
        const val TRIPS_SUBFOLDER = BackupFolderSync.TRIPS_SUBFOLDER
        const val UPLOAD_WORK_NAME = "trip_upload"
        const val PERIODIC_UPLOAD_WORK_NAME = "trip_upload_periodic"
        const val EUCSTATS_UPLOAD_WORK_NAME = "eucstats_upload"
        const val EUCSTATS_PERIODIC_WORK_NAME = "eucstats_upload_periodic"
        const val DROPBOX_SYNC_WORK_NAME = "dropbox_sync"
        const val DROPBOX_PERIODIC_WORK_NAME = "dropbox_sync_periodic"
        const val KEY_ATTEMPT = "attempt"

        // The retry curve and the refusal wait are the shared ones, so the
        // iPhone backs off exactly as these workers do.
        /** See [DropboxTripSync.WATCHDOG_ATTEMPT]. */
        const val WATCHDOG_ATTEMPT = DropboxTripSync.WATCHDOG_ATTEMPT

        /** See [DropboxTripSync.REFUSED_RETRY_SECONDS]. */
        const val REFUSED_RETRY_SECONDS = DropboxTripSync.REFUSED_RETRY_SECONDS

        fun delayForAttempt(attempt: Int): Long = DropboxTripSync.delayForAttempt(attempt)
    }

    // App-scoped so trip sync survives settings screen navigation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val alarmStore = object : AlarmRuleStore {
        override suspend fun getAll(): List<AlarmRule> = alarmDao.getAll()
        override suspend fun replaceAll(rules: List<AlarmRule>) {
            alarmDao.deleteAll()
            rules.forEach { alarmDao.insert(it.copy(id = 0)) }
        }
    }

    /** settings.json on Dropbox, shared with the iOS app. */
    private val dropboxSettings = DropboxSettingsSync(
        settingsRepository, dropboxRepository, alarmStore,
        applyLanguage = { applyRestoredLanguage(it) },
    )

    private val tripFiles = AndroidTripFiles { getTripsDir() }

    /** The backup folder: the shared passes, with SAF and WorkManager plugged in. */
    val folderSync = BackupFolderSync(
        settingsRepository, tripDao, dropboxRepository, dropboxSettings, alarmStore, tripFiles,
        access = { uri -> SafBackupFolder.open(context, uri) },
        host = object : BackupFolderHost {
            override fun scheduleUpload(attempt: Int) = scheduleTripUploadAttempt(attempt)
            override fun cancelUpload() = cancelTripUpload()
        },
    )

    /** Trips with Dropbox: the shared passes, with the backup folder and WorkManager plugged in. */
    val dropboxTrips = DropboxTripSync(
        settingsRepository, tripDao, dropboxRepository, dropboxSettings,
        tripFiles,
        object : DropboxTripHost {
            override fun scheduleRetry(attempt: Int, delaySeconds: Long?) =
                scheduleDropboxSyncAttempt(attempt, delaySeconds)
            override fun cancelRetry() {
                WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
            }
            override suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String) =
                folderSync.archiveInBackupFolder(settings, fileName)
            override suspend fun countFolderConflicts(settings: AppSettings) =
                folderSync.countBackupSubdirConflicts(settings)
            override suspend fun syncFolders(settings: AppSettings, choice: SyncChoice) =
                folderSync.syncBackupSubdirs(settings, choice)
            override suspend fun mirrorFoldersInBackground(settings: AppSettings) =
                folderSync.mirrorBackupSubdirsMissingOnDropbox(settings)
            override suspend fun snapshotBeforeRestore() { folderSync.snapshotBeforeRestore() }
            override fun enqueueFolderUpload(settings: AppSettings) = enqueueTripUpload(settings)
        },
    )

    /**
     * The current rider's store_id, read from the `.txt` recovery file in the
     * sync folder. This is the **single source of truth** for the rider's
     * online identity. We deliberately do NOT keep it in DataStore /
     * AppSettings, so the .txt and the server card together carry everything
     * a profile needs and nothing about the rider's name / flag / join date
     * ends up persisted on-device beyond what the server already holds.
     *
     * Null while the rider is unregistered, while no sync folder is
     * configured, or while the .txt is missing. Re-read on init and on any
     * `syncFolderUri` change so a fresh folder pick or an unlink updates
     * every consumer (trip upload, profile card, the restore prompt) in
     * lock-step.
     */
    private val _riderStoreId = MutableStateFlow<String?>(null)
    val riderStoreId: StateFlow<String?> = _riderStoreId.asStateFlow()

    init {
        scope.launch {
            _riderStoreId.value = readRiderIdFile()
            settingsRepository.settings
                .map { it.syncFolderUri }
                .distinctUntilChanged()
                .collect { _riderStoreId.value = readRiderIdFile() }
        }
    }

    /**
     * Start watching the pending-upload interval and (re)registering the periodic
     * safety-net worker. Called from EucPlanetApp.onCreate -- NOT from init{} --
     * because it touches WorkManager.getInstance, which pulls the app's
     * workerFactory; while the start-up singletons are still being built (when
     * init{} runs) that factory was not always ready, which crashed cold starts.
     * onCreate runs after every start-up singleton is built, so it is safe there.
     */
    fun startPendingUploadWatcher() {
        scope.launch {
            settingsRepository.settings
                .map { PeriodicUploads.of(it) }
                .distinctUntilChanged()
                .collect { plan ->
                    // A destination that is not set up gets no periodic pass:
                    // its worker would wake the app only to return at once.
                    val work = WorkManager.getInstance(context)
                    if (plan.folder) schedulePeriodicTripUpload(plan.intervalMin)
                    else work.cancelUniqueWork(PERIODIC_UPLOAD_WORK_NAME)
                    if (plan.leaderboard) schedulePeriodicEucStatsUpload(plan.intervalMin)
                    else work.cancelUniqueWork(EUCSTATS_PERIODIC_WORK_NAME)
                    if (plan.dropbox) schedulePeriodicDropboxSync(plan.intervalMin)
                    else work.cancelUniqueWork(DROPBOX_PERIODIC_WORK_NAME)
                }
        }
    }

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning.asStateFlow()

    /** True while a running sync is winding down after the rider tapped Cancel:
     *  the current file finishes, then the loop stops. Drives the "Stopping..."
     *  label and disables the Cancel button so it can't be tapped twice. */
    private val _syncCancelling = MutableStateFlow(false)
    val syncCancelling: StateFlow<Boolean> = _syncCancelling.asStateFlow()

    /** The in-flight foreground sync (folder or Dropbox). Retained so Cancel can
     *  reach it; both share _syncRunning, so at most one runs at a time. */
    private var activeSyncJob: kotlinx.coroutines.Job? = null

    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    private val _syncConflictPrompt = MutableStateFlow<Int?>(null)
    val syncConflictPrompt: StateFlow<Int?> = _syncConflictPrompt.asStateFlow()

    /** Which sync the conflict dialog is for. The same dialog handles both
     *  the SAF folder sync and Dropbox sync; the only difference is the
     *  button labels (FOLDER → "Backup", DROPBOX → "Dropbox"). */
    private val _syncConflictKind = MutableStateFlow(SyncConflictKind.FOLDER)
    val syncConflictKind: StateFlow<SyncConflictKind> = _syncConflictKind.asStateFlow()

    /** The Dropbox prompt includes settings this phone has never synced: the
     *  rider changed nothing, so the dialog asks which settings to use instead
     *  of saying they changed on both sides. */
    private val _syncConflictFirstSettings = MutableStateFlow(false)
    val syncConflictFirstSettings: StateFlow<Boolean> = _syncConflictFirstSettings.asStateFlow()

    /** Which sync is currently running, or null if idle. Lets the UI show
     *  the progress bar under the SAF section vs the Dropbox section
     *  depending on which Sync all button the rider tapped. */
    private val _activeSyncKind = MutableStateFlow<SyncConflictKind?>(null)
    val activeSyncKind: StateFlow<SyncConflictKind?> = _activeSyncKind.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()
    fun consumeSyncResult() { _syncResult.value = null }

    private var conflictChoice: CompletableDeferred<SyncChoice>? = null

    fun resolveSyncConflict(choice: SyncChoice) { conflictChoice?.complete(choice) }
    fun cancelSyncConflict() { conflictChoice?.complete(SyncChoice.CANCEL) }

    /**
     * Cancel the in-flight foreground sync (folder or Dropbox). Cooperative:
     * the current file finishes so no half-written upload is left behind, then
     * the loop stops at its next ensureActive() check and the launch's finally
     * resets state. Also releases a sync parked on the conflict dialog. Trips
     * not yet processed simply stay pending and re-sync next time. No-op if idle.
     */
    fun cancelActiveSync() {
        if (!_syncRunning.value) return
        _syncCancelling.value = true
        conflictChoice?.complete(SyncChoice.CANCEL)
        activeSyncJob?.cancel()
        // The Dropbox pass queues a watchdog run behind itself in case Android
        // takes the app away mid-sync. A rider who cancels wants it stopped,
        // not resumed five minutes later, so the queued run goes too.
        if (_activeSyncKind.value == SyncConflictKind.DROPBOX) {
            WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
            scope.launch { settingsRepository.update { it.copy(dropboxPullRequested = false) } }
        }
    }

    fun startSync() {
        if (!_syncRunning.compareAndSet(false, true)) return
        _activeSyncKind.value = SyncConflictKind.FOLDER
        activeSyncJob = scope.launch {
            try {
                // The shared pass, under the same lock as the upload worker.
                folderSync.runSync(folderUi)
            } finally {
                _syncProgress.value = null
                _syncConflictPrompt.value = null
                conflictChoice = null
                _activeSyncKind.value = null
                _syncCancelling.value = false
                _syncRunning.value = false
            }
        }
    }

    /** See [BackupFolderSync.refreshFolderConflictCount]. */
    suspend fun refreshFolderConflictCount(
        settings: AppSettings,
        folderSizes: Map<String, Long>? = null,
    ) = folderSync.refreshFolderConflictCount(settings, folderSizes)

    /** See [BackupFolderSync.recountFolderConflictsIfWarned]. */
    suspend fun recountFolderConflictsIfWarned() = folderSync.recountFolderConflictsIfWarned()

    /** The folder Sync all's question, progress bar and result, on this manager's flows. */
    private val folderUi = object : DropboxSyncUi {
        override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.FOLDER
            _syncConflictFirstSettings.value = false
            _syncConflictPrompt.value = count
            val choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            return choice
        }
        override fun progress(done: Int, total: Int) { _syncProgress.value = done to total }
        override fun result(result: SyncResult) { _syncResult.value = result }
    }

    private fun getTripsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** See [DropboxTripSync.downloadMissingTrips]. */
    suspend fun downloadMissingTrips(budgetMs: Long, isStopped: () -> Boolean): Int =
        dropboxTrips.downloadMissingTrips(budgetMs, isStopped)

    /**
     * Persist read/write permission on the picked tree URI and save it.
     * Returns true if the folder is now usable.
     */
    suspend fun setSyncFolder(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Saved, and its conflicts counted (the count belonged to the folder before this one).
            folderSync.useFolder(uri.toString())
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not take persistable permission for $uri", e)
            false
        }
    }

    suspend fun clearSyncFolder() {
        val s = settingsRepository.get()
        s.syncFolderUri?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        // No folder, no conflict count, and online upload off (the rider id
        // lives in the folder); the pending folder retries are dropped.
        folderSync.forgetFolder(riderIdInFolder = true)
        // The rider id file went with the folder: drop the leaderboard's
        // pending retries too, so we don't sit on a backed-off worker that
        // would only no-op when it finally fires.
        cancelEucStatsUpload()
        // Clear pending/failed eucstats icons; status=2 (already on the
        // leaderboard) is preserved so a rider who restores the same folder
        // doesn't lose their "uploaded" history.
        resetUnfinishedEucstatsRows()
    }

    /** The chosen folder, or null if none or no longer accessible. */
    fun getSyncFolder(settings: AppSettings): BackupFolder? = folderSync.folder(settings)

    fun getSyncFolderDisplayName(settings: AppSettings): String? = folderSync.displayName(settings)

    /** See [BackupFolderSync.backupSettings]. */
    suspend fun backupSettings(): Boolean = folderSync.backupSettings()

    /** See [BackupFolderSync.backupSettingsAs]. */
    suspend fun backupSettingsAs(name: String?, overwrite: Boolean): BackupOutcome =
        folderSync.backupSettingsAs(name, overwrite)

    /**
     * Write a rider's store_id to the recovery file ([RIDER_BACKUP_NAME]) in
     * the sync folder and publish it on [riderStoreId]. This is the
     * registration / restore path's persistence step; we don't keep the id
     * anywhere else on-device. Name / flag / avatar / stats all live on the
     * server. Returns true on success; false if there's no folder or the
     * write failed.
     */
    suspend fun writeRiderId(storeId: String): Boolean {
        val ok = folderSync.writeRiderIdFile(storeId)
        if (ok) _riderStoreId.value = storeId
        return ok
    }

    /** Delete the recovery file ([RIDER_BACKUP_NAME]). Used when the rider
     *  deletes their account so the just-deleted profile is NOT offered for
     *  "restore" on the next Join (the store_id no longer exists server-side).
     *  Also clears [riderStoreId] so every consumer sees the unregistered
     *  state immediately. Best-effort; returns true if a file was deleted. */
    suspend fun deleteRiderIdFile(): Boolean {
        val deleted = folderSync.deleteRiderIdFile() ?: false
        _riderStoreId.value = null
        return deleted
    }

    /** See [BackupFolderSync.readRiderIdFile]. */
    suspend fun readRiderIdFile(): String? = folderSync.readRiderIdFile()

    /** See [BackupFolderSync.ensureRiderIdFile]; a write publishes on [riderStoreId]. */
    suspend fun ensureRiderIdFile(storeId: String): RiderFileResult =
        folderSync.ensureRiderIdFile(storeId) { writeRiderId(it) }

    /** Read settings.json from the folder and apply, keeps current syncFolder/device fields. */
    suspend fun restoreSettings(): Boolean = restoreSettingsFrom(SETTINGS_BACKUP_NAME)

    /** Restore from the named backup file in the sync folder. */
    suspend fun restoreSettingsFrom(fileName: String): Boolean = folderSync.restoreSettingsFrom(fileName)

    /** See [DropboxSettingsSync.applySettingsFromJson]. */
    suspend fun applySettingsFromJson(bytes: ByteArray): Boolean = dropboxSettings.applySettingsFromJson(bytes)

    /**
     * Put the restored language into effect, not just into the settings row.
     *
     * Language is the one setting the app does not own: the picker writes the
     * row and hands the tag to the system, which is what actually decides
     * which strings load. A restore wrote the row alone, so the app carried on
     * in whatever language the phone is set to, and the next launch overwrote
     * the restored row to match - the rider's choice arrived and was thrown
     * away, twice over. Reinstall then restore is exactly when the row and the
     * system disagree, since a fresh install has no app locale at all.
     */
    private suspend fun applyRestoredLanguage(tag: String) {
        if (tag.isBlank()) return
        val applied = LocaleHelper.normalizeToSupportedTag(LocaleHelper.current())
        // Setting the same locale again would restart the activity for
        // nothing, and a restore that changes no language should not blink.
        if (applied == tag) return
        withContext(Dispatchers.Main) { LocaleHelper.apply(tag) }
    }

    /** See [BackupFolderSync.restoreFactoryDefaults]. */
    suspend fun restoreFactoryDefaults(): Boolean = folderSync.restoreFactoryDefaults()

    /** See [BackupFolderSync.listSettingsBackups]. */
    suspend fun listSettingsBackups(): List<BackupEntry> = folderSync.listSettingsBackups()

    /** See [BackupFolderSync.findRestorableRider]. */
    suspend fun findRestorableRider(): RestorableRider? = folderSync.findRestorableRider()

    /** See [BackupFolderSync.snapshotBeforeRestore]. */
    suspend fun snapshotBeforeRestore(): Boolean = folderSync.snapshotBeforeRestore()

    /** See [BackupFolderSync.sanitizeBackupName]. */
    fun sanitizeBackupName(raw: String): String? = BackupFolderSync.sanitizeBackupName(raw)

    /** Initial enqueue of the folder-backup worker. Retries are scheduled by the
     *  worker itself via [scheduleTripUploadAttempt]. */
    fun enqueueTripUpload(settings: AppSettings) = folderSync.enqueueUpload(settings)

    /** Initial enqueue of the eucstats worker. Retries are scheduled by the worker
     *  itself via [scheduleEucStatsUploadAttempt]. */
    fun enqueueEucStatsUpload() {
        scheduleEucStatsUploadAttempt(attempt = 0)
    }

    /** Schedule the folder worker for [attempt]. attempt=0 fires immediately;
     *  higher attempts use the [delayForAttempt] curve. Always under the same
     *  unique-work name so a fresh enqueue (new trip, manual retry) cancels and
     *  resets the retry chain. */
    fun scheduleTripUploadAttempt(attempt: Int) {
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<TripUploadWorker>()
            .setInputData(data)
            .setInitialDelay(delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Reconcile trips left pending, e.g. by an app that was closed mid-sync.
     * Called on cold app start: the worker no-ops when there is no sync folder
     * or nothing pending, so this is cheap to fire every launch and is what
     * catches an upload the rider "closed too early" -- next open, it lands.
     */
    fun reconcilePendingTripUploads() {
        // Not immediate: a job due at once started while the main thread was
        // still busy with start-up, and its onStartJob timed out (an ANR on a
        // slow phone). KEEP leaves a retry chain that is already queued alone.
        val request = OneTimeWorkRequestBuilder<TripUploadWorker>()
            .setInputData(workDataOf(KEY_ATTEMPT to 0))
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * The cold-start WorkManager calls, off the main thread: the first
     * WorkManager.getInstance builds its database and schedulers.
     */
    fun reconcileOnStart() {
        scope.launch {
            reconcilePendingTripUploads()
            reconcilePendingEucStatsUploads()
            reconcilePendingDropboxSync()
            startPendingUploadWatcher()
        }
    }

    /**
     * Background safety-net: a periodic worker that retries pending trip uploads
     * every [intervalMin] minutes (Android's floor is 15). Unlike the one-time
     * retry chain, WorkManager reschedules periodic work across app death and
     * reboots, so a stranded upload lands even if the rider never reopens the
     * app. The worker itself no-ops when nothing is pending (the rider's "if
     * there is nothing to sync, sleep"). Re-registered with UPDATE whenever the
     * interval setting changes.
     */
    fun schedulePeriodicTripUpload(intervalMin: Int) {
        val request = PeriodicWorkRequestBuilder<TripUploadWorker>(
            intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_UPLOAD_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Cancel any queued / in-backoff folder-upload work. Used when the rider
     *  unlinks the sync folder so a long-tail retry doesn't sit waiting for a
     *  destination that no longer exists. */
    fun cancelTripUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(UPLOAD_WORK_NAME)
    }

    /** Cancel any queued / in-backoff eucstats-upload work. Used when the rider
     *  toggles leaderboards off, deletes their account, or unlinks the sync
     *  folder (since the rider id lives in that folder). Without this, a retry
     *  waiting in backoff would still fire later and find the
     *  prerequisites gone; cancelling here keeps the trip icons and WorkManager
     *  state in lockstep. */
    fun cancelEucStatsUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(EUCSTATS_UPLOAD_WORK_NAME)
    }

    /**
     * Retry stranded online (eucstats) uploads, e.g. a trip queued when the app
     * was closed too early or whose retry chain ended on a cold-start bail before
     * the rider id had loaded. Mirrors [reconcilePendingTripUploads] for the
     * online path. A short initial delay lets the rider id and settings load so
     * the worker does not immediately no-op. The worker no-ops when nothing is
     * pending or the prerequisites are gone, so this is cheap every launch.
     */
    fun reconcilePendingEucStatsUploads() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<EucStatsUploadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_ATTEMPT to 0))
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EUCSTATS_UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Background safety-net for online (eucstats) uploads, mirroring
     * [schedulePeriodicTripUpload]. Retries queued / failed leaderboard uploads
     * every [intervalMin] minutes (Android floor 15), rescheduled across app
     * death and reboots, so a stranded trip lands even if the rider never
     * reopens the app or rides again. No-ops when nothing is pending.
     */
    fun schedulePeriodicEucStatsUpload(intervalMin: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<EucStatsUploadWorker>(
            intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EUCSTATS_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Push one edited trip to every configured backup, right now, in-process.
     *
     * The rider who renames a trip is looking at the row while it says
     * "Backing up", and that promise used to be handed to WorkManager -
     * which is free to sit on the job for minutes even with the network up
     * (seen on a Pixel: renamed trip parked at "Backing up", JobScheduler
     * holding the job on an unsatisfied network bit, on AC power with wifi).
     * The app is in the foreground with the network right there; use it.
     * The workers stay behind this as the retry net for everything that
     * fails or happens with the app gone.
     *
     * Overwrites the Dropbox copy without asking who changed it last, which
     * is correct here and only here: the rider has just edited this trip on
     * this phone, so this copy is the newest by definition.
     */
    fun pushEditedTripNow(tripId: Long) {
        scope.launch {
            val settings = settingsRepository.get()
            val trip = tripDao.getById(tripId) ?: return@launch
            val file = File(getTripsDir(), trip.fileName)
            if (!file.exists()) return@launch
            // The folder half is shared: upload under the pass lock, mark the
            // row, and on failure queue the worker's first retry.
            if (!folderSync.pushEditedTrip(settings, trip)) return@launch
            if (settings.dropboxAccessToken.isNotBlank()) {
                dropboxTrips.pushEditedTrip(trip.fileName)
            }
        }
    }

    /** Initial enqueue of the Dropbox sync worker. Retries reschedule
     *  themselves via [scheduleDropboxSyncAttempt]. Caller should check
     *  the linked state before calling, this is unconditional. */
    fun enqueueDropboxSync() {
        // Do NOT set the pending flag here. This runs on every app start
        // (TripRepository reconcile) whenever Dropbox is linked, so flipping it
        // true flashed the "Syncing trips…" indicator on every open even with
        // nothing to upload. The worker sets the flag from the real needUpload
        // count on its next pass, so a genuine backlog (e.g. a just-finished ride)
        // still surfaces the indicator, and an all-synced state stays quiet.
        scheduleDropboxSyncAttempt(0)
    }

    /**
     * Retry a stranded Dropbox mirror on cold start, a few seconds in so the
     * link token has loaded. Mirrors [reconcilePendingTripUploads] /
     * [reconcilePendingEucStatsUploads] for the Dropbox path, which otherwise
     * only ran on trip finish / manual Sync all and left a trip stranded if its
     * retry chain ended. The worker no-ops when Dropbox is not linked, so this
     * is cheap every launch.
     */
    fun reconcilePendingDropboxSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_ATTEMPT to 0))
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DROPBOX_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Background safety-net for the Dropbox mirror, mirroring
     * [schedulePeriodicTripUpload]. Re-mirrors local trips every [intervalMin]
     * minutes (Android floor 15), rescheduled across app death, so a stranded
     * upload lands even if the rider never reopens the app. The worker no-ops
     * when Dropbox is not linked or everything is already mirrored.
     */
    fun schedulePeriodicDropboxSync(intervalMin: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<DropboxSyncWorker>(
            intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DROPBOX_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Ask for the Dropbox library to come down, without taking over the screen.
     *
     * For linking, where the rider has just said "bring my trips over" but is
     * still in the middle of setting the app up. The worker does the fetching:
     * it takes what it can in the time it is given, leaves anything the phone
     * already has alone, and comes back for the rest.
     */
    fun requestDropboxPull() {
        scope.launch {
            settingsRepository.update { it.copy(dropboxPullRequested = true) }
            enqueueDropboxSync()
        }
    }

    /**
     * Foreground "Sync all" for Dropbox. Mirrors [startSync] but talks to
     * Dropbox: compare local trips dir against /trips/, prompt the rider
     * on shared file names with the same conflict dialog the SAF sync uses,
     * then upload / download to reconcile. Runs in the app-scoped coroutine
     * so leaving Settings does NOT cancel a half-finished reconcile.
     */
    fun startDropboxSync() {
        if (!_syncRunning.compareAndSet(false, true)) return
        _activeSyncKind.value = SyncConflictKind.DROPBOX
        // A safety net for the pass about to start. Pulling a big library takes
        // the better part of an hour, and the rider will put the phone in a
        // pocket long before that: Android reclaims the app, the coroutine dies
        // mid-loop, and the code that would have scheduled a retry never runs.
        // WorkManager outlives the process, so this is queued up front. If the
        // foreground pass finishes, the worker wakes to nothing left to do.
        scope.launch { settingsRepository.update { it.copy(dropboxPullRequested = true) } }
        scheduleDropboxSyncAttempt(WATCHDOG_ATTEMPT)
        activeSyncJob = scope.launch {
            try {
                // Marks the trips pending, takes the pass lock, runs the pass.
                dropboxTrips.syncAll(dropboxUi)
            } finally {
                _syncProgress.value = null
                _syncConflictPrompt.value = null
                conflictChoice = null
                _activeSyncKind.value = null
                _syncCancelling.value = false
                _syncRunning.value = false
            }
        }
    }

    /** Remember [bytes] as the version of [fileName] both sides now hold. */
    suspend fun markDropboxSynced(fileName: String, bytes: ByteArray) =
        dropboxTrips.markDropboxSynced(fileName, bytes)

    /** Held by every Dropbox pass, foreground or background, so two never move the same files. */
    val dropboxPassLock get() = dropboxTrips.passLock

    /** See [DropboxSettingsSync.pushIfOnlyPhoneChanged]. */
    suspend fun pushDropboxSettingsIfOnlyPhoneChanged(): Boolean = dropboxSettings.pushIfOnlyPhoneChanged()

    /** The foreground Dropbox pass's question, progress bar and result, on this manager's flows. */
    private val dropboxUi = object : DropboxSyncUi {
        override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.DROPBOX
            _syncConflictFirstSettings.value = firstSettings
            _syncConflictPrompt.value = count
            val choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            return choice
        }
        override fun progress(done: Int, total: Int) { _syncProgress.value = done to total }
        override fun result(result: SyncResult) { _syncResult.value = result }
    }

    /**
     * Move a trip's file out of the way on every destination that has it:
     * Dropbox first, the backup folder second, the phone's copy last (the
     * caller's). See [DropboxTripSync.archiveTripFile].
     *
     * @return true only when the file is archived everywhere it existed
     */
    suspend fun archiveTripFile(fileName: String): Boolean = dropboxTrips.archiveTripFile(fileName)

    /**
     * Archive many trips at once, for "delete all", in batches. See
     * [DropboxTripSync.archiveTripFiles].
     *
     * @return the file names that are now archived everywhere they existed
     */
    suspend fun archiveTripFiles(fileNames: List<String>): Set<String> = dropboxTrips.archiveTripFiles(fileNames)

    fun scheduleDropboxSyncAttempt(attempt: Int, delaySeconds: Long? = null) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setInitialDelay(delaySeconds ?: delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DROPBOX_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelDropboxSync() {
        WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
    }

    /**
     * Rider tapped Cancel on the persistent "Syncing trips…" indicator: stop
     * retrying the Dropbox mirror entirely. Cancels the background retry worker,
     * cancels a running manual Dropbox reconcile if one is in flight, and clears
     * the pending flag so the indicator disappears. Trips that were pending stay
     * local until the next sync; nothing is lost.
     */
    fun stopDropboxSync() {
        WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
        if (_activeSyncKind.value == SyncConflictKind.DROPBOX) cancelActiveSync()
        scope.launch {
            settingsRepository.update {
                it.copy(dropboxSyncPending = false, dropboxPendingCount = 0, dropboxSyncTotal = 0)
            }
        }
    }

    /** Clear pending / failed eucstats statuses for every trip. Used by the
     *  toggle-off and folder-unlink paths so the orange / red cloud icons stop
     *  appearing for trips that no longer have a path to upload. Status=2
     *  trips (already on the leaderboard) are preserved. */
    suspend fun resetUnfinishedEucstatsRows() {
        tripDao.resetUnfinishedEucstatsStatuses()
    }

    /** Clear ALL non-zero eucstats statuses, including the green ticks for
     *  previously-uploaded trips. Used only by the account-delete path: the
     *  server has nothing for this rider anymore so even status=2 is
     *  misleading. */
    suspend fun resetAllEucstatsRows() {
        tripDao.resetAllEucstatsStatuses()
    }

    /** Schedule the eucstats worker for [attempt]. See [scheduleTripUploadAttempt]
     *  for semantics; this one also carries the CONNECTED network constraint. */
    fun scheduleEucStatsUploadAttempt(attempt: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<EucStatsUploadWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setInitialDelay(delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EUCSTATS_UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** See [BackupFolderSync.listFolderTripNames]. */
    fun listFolderTripNames(settings: AppSettings): List<String>? = folderSync.listFolderTripNames(settings)

    /** See [BackupFolderSync.listFolderTripSizes]. */
    fun listFolderTripSizes(settings: AppSettings): Map<String, Long>? = folderSync.listFolderTripSizes(settings)

    /** Held by every folder pass, foreground or background; see [BackupFolderSync.withUploadPass]. */
    suspend fun <T> withUploadPass(block: suspend () -> T): T = folderSync.withUploadPass(block)
}
