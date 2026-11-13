package com.eried.eucplanet.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.MoveOutcome
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.AlarmBackupJson
import com.eried.eucplanet.data.store.SettingsJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.eried.eucplanet.util.TripCsv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Coordinates backup and sync: the SAF backup folder the rider picks (trips,
 * settings.json, themes, overlays, the eucstats rider id file), the Dropbox
 * mirror through [DropboxRepository], and the WorkManager jobs that retry both.
 *
 * The folder is picked in any DocumentsProvider with ACTION_OPEN_DOCUMENT_TREE.
 * We take persistable permission and store the tree URI in settings, and write
 * through DocumentFile.
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
        const val SETTINGS_BACKUP_NAME = "eucplanet_settings.json"
        const val SETTINGS_BACKUP_PREFIX = "eucplanet_settings-"
        const val SETTINGS_BACKUP_SUFFIX = ".json"
        // Plain-text file holding ONLY the eucstats online rider id (the
        // store_id UUID, nothing else) -- the recovery token for "found a
        // previous profile" after a reinstall / new device. Name/flag/stats all
        // come from the server, so the id is the entire identity.
        const val RIDER_BACKUP_NAME = "eucstats_riderid.txt"
        const val TRIPS_SUBFOLDER = "trips"
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

    /** settings.json on Dropbox, shared with the iOS app. */
    private val dropboxSettings = DropboxSettingsSync(
        settingsRepository, dropboxRepository,
        object : AlarmRuleStore {
            override suspend fun getAll(): List<AlarmRule> = alarmDao.getAll()
            override suspend fun replaceAll(rules: List<AlarmRule>) {
                alarmDao.deleteAll()
                rules.forEach { alarmDao.insert(it.copy(id = 0)) }
            }
        },
        applyLanguage = { applyRestoredLanguage(it) },
    )

    /** Trips with Dropbox: the shared passes, with the backup folder and WorkManager plugged in. */
    val dropboxTrips = DropboxTripSync(
        settingsRepository, tripDao, dropboxRepository, dropboxSettings,
        AndroidTripFiles { getTripsDir() },
        object : DropboxTripHost {
            override fun scheduleRetry(attempt: Int, delaySeconds: Long?) =
                scheduleDropboxSyncAttempt(attempt, delaySeconds)
            override fun cancelRetry() {
                WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
            }
            override suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String) =
                this@SyncManager.archiveInBackupFolder(settings, fileName)
            override suspend fun countFolderConflicts(settings: AppSettings) = countBackupSubdirConflicts(settings)
            override suspend fun syncFolders(settings: AppSettings, choice: SyncChoice) =
                syncBackupSubdirs(settings, choice)
            override suspend fun mirrorFoldersInBackground(settings: AppSettings) =
                mirrorBackupSubdirsMissingOnDropbox(settings)
            override suspend fun snapshotBeforeRestore() { this@SyncManager.snapshotBeforeRestore() }
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
                .map { it.pendingUploadIntervalMin }
                .distinctUntilChanged()
                .collect {
                    schedulePeriodicTripUpload(it)
                    schedulePeriodicEucStatsUpload(it)
                    schedulePeriodicDropboxSync(it)
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
                runSync()
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

    /**
     * Takes the same pass lock as the upload worker.
     *
     * Both write into the trips folder, and the app runs this on launch, which
     * is exactly when a worker mirroring a restored library is busy. Two of
     * them creating one file does not fail: the document provider renames the
     * loser, leaving "trip (1) (1).csv" beside the backup. Seen happening.
     */
    private suspend fun runSync() = withUploadPass {
        try {
            runSyncPass()
        } finally {
            // The rider just picked a side, or skipped, or cancelled: the
            // warning must say what the folder holds now, not what the worker
            // saw on its last pass. Counted even after a cancel, which is why
            // it cannot itself be cancelled.
            withContext(kotlinx.coroutines.NonCancellable) {
                val settings = settingsRepository.get()
                if (settings.syncFolderUri != null) refreshFolderConflictCount(settings)
            }
        }
    }

    /**
     * Count the trips whose phone and folder copies differ and store it for
     * the Backup conflicts warning. The one place that number is written:
     * the folder worker calls it on every pass with the listing it already
     * has, and a manual sync calls it when it ends. A folder that cannot be
     * listed leaves the number alone.
     */
    suspend fun refreshFolderConflictCount(
        settings: AppSettings,
        folderSizes: Map<String, Long>? = null,
    ) = withContext(Dispatchers.IO) {
        val folderSizes = folderSizes ?: listFolderTripSizes(settings) ?: return@withContext
        val tripsDir = getTripsDir()
        val localSizes = HashMap<String, Long>()
        for (trip in tripDao.observeAll().first()) {
            if (trip.endTime == null) continue
            val file = File(tripsDir, trip.fileName)
            if (file.exists()) localSizes[trip.fileName] = file.length()
        }
        val conflicts = countFolderConflicts(localSizes, folderSizes)
        if (settings.folderConflictCount != conflicts) {
            settingsRepository.update { it.copy(folderConflictCount = conflicts) }
        }
    }

    /**
     * Count again, but only while the warning is up. For a change on the
     * phone that can only settle a conflict, never make one: a trip deleted,
     * or the library cleared. Costs nothing when there is no warning.
     */
    suspend fun recountFolderConflictsIfWarned() {
        val settings = settingsRepository.get()
        if (settings.folderConflictCount > 0 && settings.syncFolderUri != null) {
            refreshFolderConflictCount(settings)
        }
    }

    private suspend fun runSyncPass() {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val folderSizes = listFolderTripSizes(settings)
        if (folderSizes == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val folderNames = folderSizes.keys
        val folderSizeByLower = folderSizes.mapKeys { it.key.lowercase() }

        val dbTrips = tripDao.observeAll().first()
        // Handed to uploadCsv so it does not re-list the folder per trip.
        val folderByLower = folderNames.associateBy { it.lowercase() }
        val dbByLower = dbTrips.associateBy { it.fileName.lowercase() }

        // A trip both sides hold is a conflict only when the copies differ; the
        // prompt used to count every shared name, "changed" or not, and either
        // answer then rewrote the whole library.
        val tripsDir = getTripsDir()
        val conflictKeys = (folderByLower.keys intersect dbByLower.keys).filterTo(HashSet()) { key ->
            val local = File(tripsDir, dbByLower.getValue(key).fileName)
            local.exists() && local.length() != folderSizeByLower[key]
        }
        val folderOnlyKeys = folderByLower.keys - dbByLower.keys
        val dbOnly = dbTrips.filter {
            it.endTime != null && it.fileName.lowercase() !in folderByLower.keys
        }

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.FOLDER
            _syncConflictFirstSettings.value = false
            _syncConflictPrompt.value = conflictKeys.size
            choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            if (choice == SyncChoice.CANCEL) return
        }

        val toUpload = dbOnly.toMutableList()
        val toDownload = folderOnlyKeys.mapNotNull { folderByLower[it] }.toMutableList()
        // Known trip, file gone from the phone: the folder's copy is the only one.
        (folderByLower.keys intersect dbByLower.keys)
            .filter { !File(tripsDir, dbByLower.getValue(it).fileName).exists() }
            .mapNotNullTo(toDownload) { folderByLower[it] }
        when (choice) {
            SyncChoice.APP -> toUpload.addAll(conflictKeys.mapNotNull { dbByLower[it] })
            SyncChoice.FOLDER -> toDownload.addAll(conflictKeys.mapNotNull { folderByLower[it] })
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        val total = toUpload.size + toDownload.size
        if (total == 0) {
            _syncResult.value = SyncResult.UpToDate
            return
        }

        var done = 0
        // Uploads that failed this pass (folder access lost, storage full). They
        // are marked uploadStatus=3 and retried by the folder-backup worker.
        var failed = 0
        _syncProgress.value = done to total

        for (trip in toUpload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            val file = File(getTripsDir(), trip.fileName)
            if (file.exists()) {
                val ok = uploadCsv(settings, file, knownNames = folderByLower)
                if (ok) {
                    tripDao.markUploaded(trip.id, System.currentTimeMillis())
                } else {
                    tripDao.setUploadStatus(trip.id, 3)
                    failed++
                }
            }
            done++
            _syncProgress.value = done to total
        }

        for (fileName in toDownload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            val destFile = File(getTripsDir(), fileName)
            if (downloadCsv(settings, fileName, destFile)) {
                val meta = TripCsvMeta.read(AndroidTripFile(destFile))
                val existing = dbByLower[fileName.lowercase()]
                if (existing != null) {
                    tripDao.update(existing.copy(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        distanceKm = meta.distanceKm,
                        // Take the file's name when it has one, and keep the
                        // rider's when it does not: an older copy without the
                        // Extra cell must not wipe a name set on this phone.
                        customName = meta.name ?: existing.customName,
                        wheelMetaJson = meta.wheelJson ?: existing.wheelMetaJson,
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                } else {
                    tripDao.insert(TripRecord(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        fileName = fileName,
                        distanceKm = meta.distanceKm,
                        // The file carries the rider's name for it; a downloaded
                        // trip used to arrive nameless and show its date instead.
                        customName = meta.name,
                        wheelMetaJson = meta.wheelJson,
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        // Failed uploads are uploadStatus=3, which the folder-backup worker
        // retries, so kick it so they keep retrying in the background.
        // The result is always Finished -- no error toast; failed folder uploads
        // finish silently once conditions recover.
        if (failed > 0) enqueueTripUpload(settings)
        _syncResult.value = SyncResult.Finished(total)
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
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(syncFolderUri = uri.toString()))
            // The count belonged to the folder before this one.
            refreshFolderConflictCount(settingsRepository.get())
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
        settingsRepository.update(s.copy(
            syncFolderUri = null,
            lastSettingsBackupAt = null,
            onlineUploadEnabled = false,  // online upload requires a folder
            folderConflictCount = 0,      // no folder, nothing to differ from
        ))
        // Both destinations just lost their prerequisites: folder is gone, and
        // the rider id file went with it. Drop any pending retries so we don't
        // sit on a backed-off worker that would only no-op when it finally fires.
        cancelTripUpload()
        cancelEucStatsUpload()
        // Clear pending/failed eucstats icons; status=2 (already on the
        // leaderboard) is preserved so a rider who restores the same folder
        // doesn't lose their "uploaded" history.
        resetUnfinishedEucstatsRows()
    }

    /** The chosen folder's DocumentFile, or null if none or no longer accessible. */
    fun getSyncFolder(settings: AppSettings): DocumentFile? {
        val uriStr = settings.syncFolderUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.takeIf { it.canWrite() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open sync folder", e)
            null
        }
    }

    fun getSyncFolderDisplayName(settings: AppSettings): String? {
        val folder = getSyncFolder(settings) ?: return null
        // Prefer the decoded tree path so the user sees where the folder lives
        // (e.g. "Internal storage › Documents › EUC Planet") instead of just the leaf.
        val treePath = folder.uri.lastPathSegment
        if (!treePath.isNullOrEmpty()) {
            val parts = treePath.split(":", limit = 2)
            val volume = when (parts.getOrNull(0)) {
                "primary" -> "Internal storage"
                null, "" -> null
                else -> parts[0]
            }
            val path = parts.getOrNull(1).orEmpty().trim('/')
            val segments = if (path.isEmpty()) emptyList() else path.split('/')
            val all = listOfNotNull(volume) + segments
            if (all.isNotEmpty()) return all.joinToString(" › ")
        }
        return folder.name
    }

    /** Serialise AppSettings + alarm rules to JSON and write to SETTINGS_BACKUP_NAME. */
    suspend fun backupSettings(): Boolean =
        backupSettingsAs(name = null, overwrite = true) == BackupOutcome.Saved

    /**
     * Write a named backup file in the sync folder. [name] = null is the
     * default `eucplanet_settings.json`; a non-null sanitised name produces
     * `eucplanet_settings-{name}.json`. When [overwrite] is false and the
     * target already exists, returns [BackupOutcome.AlreadyExists] without
     * touching the file so the caller can prompt the rider.
     */
    suspend fun backupSettingsAs(name: String?, overwrite: Boolean): BackupOutcome {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return BackupOutcome.Failed
        val fileName = buildBackupFileName(name)
        val existing = folder.findFile(fileName)
        if (existing != null && !overwrite) return BackupOutcome.AlreadyExists
        val payload = SettingsJson.toJson(SettingsJson.stripDeviceBindings(current)).apply {
            put("alarms", AlarmBackupJson.alarmsToJson(alarmDao.getAll()))
        }
        val json = payload.toString(2)
        return try {
            existing?.delete()
            val file = folder.createFile("application/json", fileName)
                ?: return BackupOutcome.Failed
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return BackupOutcome.Failed
            // Every successful backup updates the "last backup" label so the
            // rider sees "Last backup: <date> AS <name>" right after a named
            // save. Name is null for the default snapshot.
            settingsRepository.update(
                current.copy(
                    lastSettingsBackupAt = System.currentTimeMillis(),
                    lastSettingsBackupName = name
                )
            )
            BackupOutcome.Saved
        } catch (e: Exception) {
            Log.e(TAG, "Settings backup failed", e)
            BackupOutcome.Failed
        }
    }

    /**
     * Write a rider's store_id to the recovery file ([RIDER_BACKUP_NAME]) in
     * the sync folder and publish it on [riderStoreId]. This is the
     * registration / restore path's persistence step; we don't keep the id
     * anywhere else on-device. Name / flag / avatar / stats all live on the
     * server. Returns true on success; false if there's no folder or the
     * write failed.
     */
    suspend fun writeRiderId(storeId: String): Boolean {
        val folder = getSyncFolder(settingsRepository.get()) ?: return false
        val ok = try {
            folder.findFile(RIDER_BACKUP_NAME)?.delete()
            val file = folder.createFile("text/plain", RIDER_BACKUP_NAME)
                ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(storeId.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rider id write failed", e)
            false
        }
        if (ok) _riderStoreId.value = storeId
        return ok
    }

    /** Delete the recovery file ([RIDER_BACKUP_NAME]). Used when the rider
     *  deletes their account so the just-deleted profile is NOT offered for
     *  "restore" on the next Join (the store_id no longer exists server-side).
     *  Also clears [riderStoreId] so every consumer sees the unregistered
     *  state immediately. Best-effort; returns true if a file was deleted. */
    suspend fun deleteRiderIdFile(): Boolean {
        val folder = getSyncFolder(settingsRepository.get()) ?: run {
            _riderStoreId.value = null
            return false
        }
        val deleted = folder.findFile(RIDER_BACKUP_NAME)?.delete() ?: false
        _riderStoreId.value = null
        return deleted
    }

    /** Read the plain-text online rider id (store_id) from RIDER_BACKUP_NAME, or null. */
    suspend fun readRiderIdFile(): String? {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return null
        val file = folder.findFile(RIDER_BACKUP_NAME) ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.use {
                String(it.readBytes(), Charsets.UTF_8).trim()
            }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read rider id file", e)
            null
        }
    }

    /**
     * Ensure the recovery file holds [storeId]. Used at registration / link
     * time so the rider's identity is hardened in the sync folder before
     * anything else can clobber it. Leaves a foreign rider's file untouched
     * and reports [RiderFileResult.MISMATCH] so the caller can warn.
     */
    suspend fun ensureRiderIdFile(storeId: String): RiderFileResult {
        getSyncFolder(settingsRepository.get()) ?: return RiderFileResult.SKIPPED
        return when (readRiderIdFile()) {
            storeId -> RiderFileResult.ALREADY_PRESENT
            null -> if (writeRiderId(storeId)) RiderFileResult.WROTE else RiderFileResult.SKIPPED
            else -> RiderFileResult.MISMATCH
        }
    }

    /** Read settings.json from the folder and apply, keeps current syncFolder/device fields. */
    suspend fun restoreSettings(): Boolean = restoreSettingsFrom(SETTINGS_BACKUP_NAME)

    /** Restore from the named backup file in the sync folder. */
    suspend fun restoreSettingsFrom(fileName: String): Boolean {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return false
        val file = folder.findFile(fileName) ?: return false
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: return false
        return applySettingsFromJson(bytes)
    }

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

    /**
     * Reset rider configuration to factory defaults. Reuses the file-restore
     * merge with an in-memory [AppSettings] snapshot instead of a backup file:
     * [SettingsJson.stripDeviceBindings] drops the device + sync fields from the
     * factory JSON, so [SettingsJson.fromJson] keeps the rider's current
     * pairings, sync folder and backup history while every other field reverts
     * to its default. Custom alarm rules are cleared (a fresh install ships
     * none). Needs no sync folder, it's a purely local reset.
     */
    suspend fun restoreFactoryDefaults(): Boolean {
        val current = settingsRepository.get()
        return try {
            val factoryJson = SettingsJson.toJson(SettingsJson.stripDeviceBindings(AppSettings()))
            // Factory reset keeps device bindings (pairings, sync folder) and
            // the live Dropbox link, same as the restore path above.
            val parsedReset = SettingsJson.fromJson(factoryJson, current)
            val reset = parsedReset.copy(
                dropboxAccessToken = current.dropboxAccessToken,
                dropboxRefreshToken = current.dropboxRefreshToken,
                dropboxAccessTokenExpiresAt = current.dropboxAccessTokenExpiresAt,
                dropboxAccountLabel = current.dropboxAccountLabel,
                dropboxLastSyncAt = current.dropboxLastSyncAt,
                share = parsedReset.share.copy(deviceSecret = current.share.deviceSecret),
            )
            settingsRepository.update(reset)
            alarmDao.deleteAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed", e)
            false
        }
    }

    /**
     * List every settings backup in the sync folder. The default
     * `eucplanet_settings.json` is always returned first (with [BackupEntry.label]
     * = null), followed by named snapshots sorted by display label.
     */
    suspend fun listSettingsBackups(): List<BackupEntry> {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return emptyList()
        val out = mutableListOf<BackupEntry>()
        val named = mutableListOf<BackupEntry>()
        folder.listFiles().forEach { doc ->
            val n = doc.name ?: return@forEach
            if (!n.endsWith(".json", ignoreCase = true)) return@forEach
            when {
                n.equals(SETTINGS_BACKUP_NAME, ignoreCase = true) -> {
                    out += BackupEntry(fileName = n, label = null)
                }
                n.startsWith(SETTINGS_BACKUP_PREFIX, ignoreCase = true) &&
                    n.length > SETTINGS_BACKUP_PREFIX.length + SETTINGS_BACKUP_SUFFIX.length -> {
                    val label = n.substring(
                        SETTINGS_BACKUP_PREFIX.length,
                        n.length - SETTINGS_BACKUP_SUFFIX.length
                    )
                    if (label.isNotEmpty()) named += BackupEntry(fileName = n, label = label)
                }
            }
        }
        named.sortBy { it.label?.lowercase() }
        return out + named
    }

    /**
     * The recoverable rider identity for this sync folder. The store_id in
     * `eucstats_riderid.txt` is the only thing the app needs to identify the
     * rider on reinstall. The display name and the rest of the profile come
     * from `api.getCard(storeId)` once the rider opts in to restore. Returns
     * null when no folder is configured, no `.txt` file is present, or the
     * file is empty.
     */
    suspend fun findRestorableRider(): RestorableRider? {
        val id = readRiderIdFile() ?: return null
        return RestorableRider(fileName = RIDER_BACKUP_NAME, storeId = id)
    }

    /**
     * Save a timestamped safety copy of the CURRENT settings before a restore
     * that would replace the rider identity, so the previous rider stays
     * recoverable even if the rider taps through the confirm. Best-effort.
     */
    suspend fun snapshotBeforeRestore(): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return backupSettingsAs(name = "before-restore-$stamp", overwrite = false) == BackupOutcome.Saved
    }

    /** Path-safe sanitiser. Strips anything that isn't [A-Za-z0-9_- ], trims,
     *  collapses whitespace, caps at 32 chars. Empty input returns null so the
     *  caller can show a validation error. */
    fun sanitizeBackupName(raw: String): String? {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(32)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun buildBackupFileName(name: String?): String =
        if (name == null) SETTINGS_BACKUP_NAME else "$SETTINGS_BACKUP_PREFIX$name$SETTINGS_BACKUP_SUFFIX"

    /** Initial enqueue of the folder-backup worker. Retries are scheduled by the
     *  worker itself via [scheduleTripUploadAttempt]. */
    fun enqueueTripUpload(settings: AppSettings) {
        if (settings.syncFolderUri == null) return
        scheduleTripUploadAttempt(attempt = 0)
    }

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
            if (settings.syncFolderUri != null) {
                val ok = runCatching { withUploadPass { uploadCsv(settings, file) } }
                    .getOrDefault(false)
                tripDao.update((tripDao.getById(tripId) ?: return@launch).copy(
                    uploadStatus = if (ok) 2 else 3,
                    uploadedAt = if (ok) System.currentTimeMillis() else trip.uploadedAt,
                ))
                if (!ok) scheduleTripUploadAttempt(1)
            }
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
     * The backup folder's [sub] as the shared [syncJsonFolder] sees it. Only
     * .json files take part: a theme or overlay is JSON, and anything else
     * would be renamed by the provider on the way in and come back every pass.
     */
    private fun safJsonFolder(
        folder: androidx.documentfile.provider.DocumentFile, sub: String, create: Boolean,
    ): JsonFolder = object : JsonFolder {
        private var dir: androidx.documentfile.provider.DocumentFile? = null
        private var docs: Map<String, androidx.documentfile.provider.DocumentFile> = emptyMap()

        override suspend fun jsonFiles(): Map<String, ByteArray>? {
            val d = folder.findFile(sub)?.takeIf { it.isDirectory }
                ?: (if (create) folder.createDirectory(sub) else null) ?: return null
            dir = d
            docs = d.listFiles()
                .filter { it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true }
                .associateBy { it.name!! }
            // A file the provider will not open is left out rather than sent
            // up empty.
            return docs.mapNotNull { (name, doc) ->
                context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }?.let { name to it }
            }.toMap()
        }

        override suspend fun write(name: String, bytes: ByteArray): Boolean {
            val d = dir ?: return false
            // Replace an existing file in place. Deleting it and
            // creating it again races the provider, which then names
            // the new one "name (1).json" beside a missing original.
            val existing = docs[name]
            val target = existing ?: d.createFile("application/json", name) ?: return false
            val ok = try {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes); true } ?: false
            } catch (e: Exception) { false }
            if (!ok) {
                // A half-created empty file would look like a conflict next pass and could
                // overwrite the real Dropbox copy on an APP choice. Remove it so a failed
                // download simply retries next time.
                if (existing == null) target.delete()
            }
            return ok
        }
    }

    /**
     * Sync the backup folder's themes/ and overlays/ both ways with Dropbox
     * ([syncJsonFolder], shared with iOS). Downloads write into the SAF
     * subfolder, where ThemeStore/OverlayPresetStore read them.
     * @return number of files transferred (not counted as trips).
     */
    private suspend fun syncBackupSubdirs(settings: AppSettings, choice: SyncChoice): Int {
        val folder = getSyncFolder(settings) ?: return 0
        var count = 0
        for (sub in listOf("themes", "overlays")) {
            try {
                count += syncJsonFolder(dropboxRepository, sub, safJsonFolder(folder, sub, create = true), choice) ?: 0
            } catch (e: Exception) {
                android.util.Log.w(TAG, "sync /$sub failed: ${e.message}")
            }
        }
        return count
    }

    /** List both subfolders and count conflicts only (no transfers), so the
     *  ONE prompt can include themes/overlays conflicts before it shows. */
    private suspend fun countBackupSubdirConflicts(settings: AppSettings): Int {
        val folder = getSyncFolder(settings) ?: return 0
        var n = 0
        for (sub in listOf("themes", "overlays")) {
            n += try {
                safJsonFolder(folder, sub, create = false).jsonFiles()
                    ?.let { planJsonFolder(dropboxRepository, sub, it) }?.conflicts?.size ?: 0
            } catch (e: Exception) {
                android.util.Log.w(TAG, "count /$sub failed: ${e.message}")
                0
            }
        }
        return n
    }

    /**
     * The background pass's mirror of the backup folder's themes/ and
     * overlays/, so the cloud copy is the WHOLE folder, not just trips and
     * settings. These live as files in the SAF backup folder (ThemeStore /
     * OverlayPresetStore). The background only adds what Dropbox lacks:
     * overwriting by modified time silently replaced another phone's edit, so
     * a file both sides hold is left to Sync all and its prompt.
     */
    private suspend fun mirrorBackupSubdirsMissingOnDropbox(settings: AppSettings): FolderMirror {
        var uploaded = 0
        var anyFailed = false
        val folder = getSyncFolder(settings) ?: return FolderMirror(0, false)
        for (sub in listOf("themes", "overlays")) {
            try {
                val subDir = folder.findFile(sub)?.takeIf { it.isDirectory } ?: continue
                // A failed listing is not an empty folder: skip, do not upload everything.
                val remoteSub = dropboxRepository.listFolder("/$sub") ?: continue
                val remoteLower = remoteSub.keys.mapTo(HashSet()) { it.lowercase() }
                for (doc in subDir.listFiles()) {
                    if (!doc.isFile) continue
                    val name = doc.name ?: continue
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    if (name.lowercase() in remoteLower) continue
                    val bytes = try {
                        context.contentResolver
                            .openInputStream(doc.uri)?.use { it.readBytes() }
                    } catch (e: Exception) { null }
                    if (bytes == null) { anyFailed = true; continue }
                    if (dropboxRepository.uploadFile("/$sub/$name", bytes)) {
                        uploaded++
                        Log.i("DropboxSyncWorker", "Uploaded /$sub/$name")
                    } else anyFailed = true
                }
            } catch (e: Exception) {
                Log.w("DropboxSyncWorker", "mirror /$sub failed: ${e.message}")
            }
        }
        return FolderMirror(uploaded, anyFailed)
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
     * A name nothing in [archive] is using yet: "trip.csv", then "trip (1).csv".
     *
     * The same shape Dropbox's autorename produces, so a rider looking at the
     * two archives sees the same thing in both.
     */
    private fun freeArchiveName(archive: DocumentFile, fileName: String): String {
        if (archive.findFile(fileName) == null) return fileName
        val stem = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 1
        while (n < 1000) {
            val candidate = "$stem ($n)$suffix"
            if (archive.findFile(candidate) == null) return candidate
            n++
        }
        return fileName
    }

    /**
     * Archive many trips at once, for "delete all", in batches. See
     * [DropboxTripSync.archiveTripFiles].
     *
     * @return the file names that are now archived everywhere they existed
     */
    suspend fun archiveTripFiles(fileNames: List<String>): Set<String> = dropboxTrips.archiveTripFiles(fileNames)

    /**
     * Move [fileName] into trips/archive inside the backup folder.
     *
     * Trips live in the folder's trips/ subdirectory, the same place
     * listFolderTripNames and downloadCsv read them from, so the archive sits
     * beside them rather than at the root next to themes/ and overlays/.
     *
     * @return true when the file was moved, or when there was nothing to move
     */
    private fun archiveInBackupFolder(settings: AppSettings, fileName: String): Boolean {
        val tripsDir = getSyncFolder(settings)
            ?.findFile(TRIPS_SUBFOLDER)?.takeIf { it.isDirectory }
            ?: return true
        return try {
            // Shared storage ignores case and the folder may spell the name
            // its own way ("Inmotion_V14" for the app's "inmotion_v14").
            // findFile matches exactly, so such a copy read as "nothing to
            // move", stayed in the folder, and the next sync brought the
            // deleted trip back.
            val doc = (tripsDir.findFile(fileName)
                ?: tripsDir.listFiles().firstOrNull { it.name.equals(fileName, ignoreCase = true) })
                ?.takeIf { it.isFile } ?: return true
            val archive = tripsDir.findFile("archive")?.takeIf { it.isDirectory }
                ?: tripsDir.createDirectory("archive") ?: return false
            // An archive never destroys: a name already in there is an earlier
            // ride the rider archived, and a second one of the same name - the
            // same library restored and cleared again - has to sit beside it,
            // not on top of it. Dropbox does this for us with autorename; here
            // it has to be worked out, so the two sides behave the same way.
            val free = freeArchiveName(archive, fileName)
            // A provider-side move is a rename: no bytes read, no bytes
            // written. Only usable when the name is untaken, since the move
            // cannot rename as it goes. Every provider is allowed to refuse it,
            // so the copy below stays as the fallback.
            if (free == fileName) {
                val moved = runCatching {
                    android.provider.DocumentsContract.moveDocument(
                        context.contentResolver, doc.uri, tripsDir.uri, archive.uri
                    )
                }.getOrNull()
                if (moved != null) return true
            }
            val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: return false
            val dest = archive.createFile("text/csv", free) ?: return false
            val wrote = runCatching {
                context.contentResolver.openOutputStream(dest.uri, "wt")?.use { it.write(bytes) }
                true
            }.getOrDefault(false)
            if (wrote) doc.delete() else false
        } catch (e: Exception) {
            Log.w(TAG, "archive in backup folder failed for $fileName: ${e.message}")
            false
        }
    }

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

    /**
     * List CSV filenames in the trips subfolder. Returns null if the folder
     * is unavailable, empty list if the folder exists but has no trips yet.
     */
    fun listFolderTripNames(settings: AppSettings): List<String>? =
        listFolderTripSizes(settings)?.keys?.toList()

    /**
     * Delete the renamed duplicates a case mismatch used to leave in the
     * backup folder's trips, see [RenamedCopyCleanup]. Names and sizes come
     * from one provider query, and files are only read when a "(N)" copy has a
     * same-sized candidate, so a clean folder costs one listing. Returns how
     * many copies went.
     */
    fun removeRenamedTripCopies(settings: AppSettings): Int {
        val root = getSyncFolder(settings) ?: return 0
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return 0
        val resolver = context.contentResolver
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            trips.uri, android.provider.DocumentsContract.getDocumentId(trips.uri))
        val ids = HashMap<String, String>()
        val entries = mutableListOf<RenamedCopyCleanup.Entry>()
        try {
            resolver.query(children, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".csv", ignoreCase = true)) continue
                    ids[name] = c.getString(0)
                    entries += RenamedCopyCleanup.Entry(name, c.getLong(2))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the trips folder for duplicates", e)
            return 0
        }
        fun uriOf(name: String) =
            android.provider.DocumentsContract.buildDocumentUriUsingTree(trips.uri, ids.getValue(name))
        fun bytes(name: String) = resolver.openInputStream(uriOf(name))?.use { it.readBytes() }
        val doomed = RenamedCopyCleanup.plan(entries) { a, b ->
            val x = bytes(a.name); x != null && x.contentEquals(bytes(b.name))
        }
        var removed = 0
        for (name in doomed) {
            val ok = try {
                android.provider.DocumentsContract.deleteDocument(resolver, uriOf(name))
            } catch (e: Exception) { false }
            if (ok) removed++
        }
        if (removed > 0) Log.i(TAG, "Removed $removed duplicate trip copies from the backup folder")
        return removed
    }

    /** CSV name -> byte size for the trips subfolder, in one directory walk.
     *  Sizes are what tell an already-backed-up trip from a conflict: same
     *  name, different bytes means the two sides disagree and neither may be
     *  overwritten without the rider choosing. */
    fun listFolderTripSizes(settings: AppSettings): Map<String, Long>? {
        val root = getSyncFolder(settings) ?: return null
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (doc in trips.listFiles()) {
            val name = doc.name ?: continue
            if (doc.isFile && name.endsWith(".csv", ignoreCase = true)) out[name] = doc.length()
        }
        return out
    }

    /**
     * Serialises folder-upload passes.
     *
     * The one-time upload worker and the periodic one are separate unique work
     * names, so WorkManager is free to run both at once, and both walk the same
     * pending list. Two passes creating the same file is not a harmless race:
     * the document provider renames the loser rather than refusing it, leaving
     * "trip (1).csv" beside the real backup. They share a process, so a lock is
     * all it takes.
     */
    private val uploadPassLock = kotlinx.coroutines.sync.Mutex()

    suspend fun <T> withUploadPass(block: suspend () -> T): T =
        uploadPassLock.withLock { block() }

    /** Copy a folder CSV into destFile. */
    fun downloadCsv(settings: AppSettings, fileName: String, destFile: File): Boolean {
        val root = getSyncFolder(settings) ?: return false
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return false
        val src = trips.findFile(fileName) ?: return false
        return try {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                destFile.outputStream().use { out -> input.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $fileName", e)
            false
        }
    }

    /**
     * Write a single CSV file to trips subfolder, returning true on success.
     * Called from the worker.
     */
    fun uploadCsv(
        settings: AppSettings,
        localFile: java.io.File,
        /** Leave a file of the same name alone instead of replacing it. Set for
         *  trips that came down from Dropbox: the folder's copy may be a
         *  different version and is not ours to overwrite unasked. */
        skipIfPresent: Boolean = false,
        /**
         * The names the folder already held when the pass started, if the
         * caller has them.
         *
         * findFile lists the whole directory to answer one question, so a
         * caller looping over a library pays that once per trip: with 2000
         * trips backed up it slowed to eight files a minute and got slower as
         * the folder filled. A caller that already enumerated the folder can
         * hand the names over, and the common case - a name the folder does
         * not have - then costs nothing. Keyed by the lowercased name, valued
         * by the folder's own spelling: shared storage ignores case, so a trip
         * the app spells "inmotion_v14" IS the folder's "Inmotion_V14", and
         * treating it as missing made the provider add "(1)", "(2)"... on
         * every pass until it refused at 32.
         */
        knownNames: Map<String, String>? = null,
    ): Boolean {
        val root = getSyncFolder(settings) ?: return false
        val tripsFolder = root.findFile(TRIPS_SUBFOLDER)
            ?: root.createDirectory(TRIPS_SUBFOLDER)
            ?: return false
        return try {
            val folderSpelling = knownNames?.get(localFile.name.lowercase())
            val knownHas = knownNames?.let { folderSpelling != null }
            // Nothing to do, and the caller's listing already proves it. Going
            // to the folder to confirm costs a full directory listing, which is
            // the whole reason mirroring a restored library crawled: most of
            // those trips are already backed up.
            if (knownHas == true && skipIfPresent) return true
            val existing = when {
                knownHas == false -> null
                folderSpelling != null -> tripsFolder.findFile(folderSpelling)
                else -> tripsFolder.listFiles().firstOrNull { it.name.equals(localFile.name, ignoreCase = true) }
            }
            if (existing != null && skipIfPresent) return true
            existing?.delete()
            val dest = tripsFolder.createFile("text/csv", localFile.name) ?: return false
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                localFile.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${localFile.name}", e)
            false
        }
    }
}
