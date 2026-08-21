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
import com.eried.eucplanet.data.store.SettingsJson
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates cloud sync via the Storage Access Framework.
 *
 * The user picks a folder in any DocumentsProvider (OneDrive, Google Drive,
 * Dropbox, local device storage, etc.) with ACTION_OPEN_DOCUMENT_TREE. We take
 * persistable permission and store the tree URI in settings. Trip CSVs and a
 * `settings.json` backup are written through DocumentFile, no provider-specific
 * code, no OAuth, the cloud app handles the upload.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
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

        // Custom backoff curve, since WorkManager's native MAX_BACKOFF_MILLIS is
        // a hard-coded 5h and the workers schedule their own next attempt rather
        // than returning Result.retry().
        //
        // attempt 0  → immediate (initial enqueue)
        // attempt 1  → 15s
        // attempt 2  → 30s
        // attempt 3  → 1m
        // attempt 4  → 2m
        // attempt 5  → 4m
        // attempt 6+ → 5m (capped, retries forever)
        //
        // Capped at 5m, not 1h: the CONNECTED constraint already holds a retry
        // until the network is back, but the initial delay is a floor, so a trip
        // whose attempt counter climbed while connectivity flapped mid-ride would
        // otherwise sit up to an hour before the next attempt even once wifi is
        // back home. A 5m ceiling means a stranded trip catches up within minutes.
        // The workers no-op once nothing is pending, so a tighter cap costs
        // nothing in the common case.
        /** Attempt number for the watchdog queued when a foreground sync
         *  starts: far enough up the backoff curve to sit ~5 minutes behind
         *  it, so it only ever runs if the foreground pass stopped. */
        const val WATCHDOG_ATTEMPT = 8
        private const val BACKOFF_BASE_SECONDS = 15L
        private const val BACKOFF_MAX_SECONDS = 300L

        /**
         * How long to leave Dropbox alone after it refuses us outright.
         *
         * Long on purpose: a refusal is not a hiccup, and retrying into one
         * every minute is what earns a longer one.
         */
        const val REFUSED_RETRY_SECONDS = 30L * 60

        fun delayForAttempt(attempt: Int): Long {
            if (attempt <= 0) return 0L
            val shift = (attempt - 1).coerceAtMost(20)
            val raw = BACKOFF_BASE_SECONDS * (1L shl shift)
            return raw.coerceAtMost(BACKOFF_MAX_SECONDS)
        }
    }

    // App-scoped so trip sync survives settings screen navigation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * workerFactory; during Hilt field injection (when init{} runs) that lateinit
     * may not be set yet, which crashed cold starts. onCreate runs after all
     * fields are injected, so it is safe there.
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
    private suspend fun runSync() = withUploadPass { runSyncPass() }

    private suspend fun runSyncPass() {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val folderNames = listFolderTripNames(settings)
        if (folderNames == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }

        val dbTrips = tripDao.observeAll().first()
        val folderByLower = folderNames.associateBy { it.lowercase() }
        // Handed to uploadCsv so it does not re-list the folder per trip.
        val folderNameSet = folderNames.toSet()
        val dbByLower = dbTrips.associateBy { it.fileName.lowercase() }

        val conflictKeys = folderByLower.keys intersect dbByLower.keys
        val folderOnlyKeys = folderByLower.keys - dbByLower.keys
        val dbOnly = dbTrips.filter {
            it.endTime != null && it.fileName.lowercase() !in folderByLower.keys
        }

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.FOLDER
            _syncConflictPrompt.value = conflictKeys.size
            choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            if (choice == SyncChoice.CANCEL) return
        }

        val toUpload = dbOnly.toMutableList()
        val toDownload = folderOnlyKeys.mapNotNull { folderByLower[it] }.toMutableList()
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
                val ok = uploadCsv(settings, file, knownNames = folderNameSet)
                if (ok) {
                    tripDao.update(trip.copy(
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                } else {
                    tripDao.update(trip.copy(uploadStatus = 3))
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
                val meta = parseCsvMeta(destFile)
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
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        // Failed uploads are uploadStatus=3; the folder-backup worker retries
        // status IN (1,3), so kick it so they keep retrying in the background.
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

    private data class CsvMeta(
        val startTime: Long,
        val endTime: Long,
        val distanceKm: Float,
        /**
         * The rider's name for the trip, carried in the CSV's Extra column as
         * `trip.name=`.
         *
         * A rename writes it into the file precisely so it survives export and
         * Dropbox, but nothing ever read it back: a trip arriving by download
         * was inserted nameless and shown as its date. Rename a trip, let it
         * sync, come back to it on a new phone or after a restore, and the
         * name was gone - the file had it all along.
         */
        val name: String? = null,
    )

    /**
     * Pull the trips Dropbox has and this phone does not, for up to [budgetMs].
     *
     * The background worker owns the upload half; this is the download half it
     * never had. A rider setting up a new phone from a big Dropbox library could
     * only get it through the foreground sync, which at real-world speed runs
     * for the better part of an hour - so it only finished if they sat and
     * watched it, and nothing continued once Android reclaimed the app.
     *
     * Time-bounded rather than count-bounded, because WorkManager gives a job
     * about ten minutes: take what fits, report what is left, and let the
     * worker be run again. Already-downloaded trips are skipped, so each run
     * picks up where the last one stopped.
     *
     * @return how many trips are still missing when the budget ran out
     */
    suspend fun downloadMissingTrips(budgetMs: Long, isStopped: () -> Boolean): Int {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) return 0
        // Only ever finishing a pull the rider asked for. Downloading on the
        // app's own initiative would spend a rider's data on a library they may
        // have linked Dropbox only to back up.
        if (!settings.dropboxPullRequested) return 0
        val remote = dropboxRepository.listFolder("/trips") ?: return 0
        val tripsDir = getTripsDir()
        val localLower = tripsDir.listFiles { f -> f.isFile }
            ?.map { it.name.lowercase() }?.toHashSet().orEmpty()
        val missing = remote.keys.filter { it.lowercase() !in localLower }
        if (missing.isEmpty()) {
            // Nothing left to bring over: the request is finished.
            settingsRepository.update { it.copy(dropboxPullRequested = false) }
            return 0
        }

        val deadline = System.currentTimeMillis() + budgetMs
        var left = missing.size
        // The rider sees the same persistent indicator a foreground pass puts
        // up. Without it a background download is invisible: trips appear in
        // the list with nothing to say where they came from or how many are
        // still on the way.
        settingsRepository.update {
            it.copy(
                dropboxSyncPending = true,
                dropboxPendingCount = missing.size,
                dropboxSyncTotal = missing.size,
            )
        }
        for (name in missing) {
            if (isStopped() || System.currentTimeMillis() > deadline) break
            val bytes = dropboxRepository.downloadFile("/trips/$name") ?: continue
            val dest = File(tripsDir, name)
            dest.outputStream().use { it.write(bytes) }
            // Same reason as the foreground pass: a file wearing this moment's
            // timestamp reads as edited here, and a whole pulled library would
            // be uploaded straight back.
            remote[name]?.let { dest.setLastModified(it.serverModifiedSec * 1000L) }
            if (tripDao.findByFileName(name) == null) {
                val meta = parseCsvMeta(dest)
                tripDao.insert(TripRecord(
                    startTime = meta.startTime,
                    endTime = meta.endTime,
                    fileName = name,
                    distanceKm = meta.distanceKm,
                    // The file carries the rider's name for it; a downloaded
                    // trip used to arrive nameless and show its date instead.
                    customName = meta.name,
                    // Pending when a backup folder exists, so the folder worker
                    // mirrors it: same rule as the foreground pass.
                    uploadStatus = if (settings.syncFolderUri != null) 4 else 0,
                ))
            }
            left--
            settingsRepository.update { it.copy(dropboxPendingCount = left) }
        }
        if (left < missing.size && settings.syncFolderUri != null) enqueueTripUpload(settings)
        settingsRepository.update {
            it.copy(
                dropboxSyncPending = left > 0,
                dropboxPendingCount = left,
                dropboxSyncTotal = if (left > 0) it.dropboxSyncTotal else 0,
                dropboxPullRequested = left > 0,
            )
        }
        return left
    }

    private fun parseCsvMeta(file: File): CsvMeta {
        var startTime = System.currentTimeMillis()
        var endTime = startTime
        var gpsDistanceKm = 0.0
        var tripName: String? = null
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var minMileage = Float.MAX_VALUE
        var maxMileage = 0f
        try {
            // Stream the CSV line-by-line instead of readText().lines(), so a
            // long trip (a big CSV) never has to sit fully in memory at once.
            file.bufferedReader().use { reader ->
                val headerLine = reader.readLine() ?: return CsvMeta(startTime, endTime, 0f)
                val header = headerLine.lowercase().split(",").map { it.trim() }
                val extraIdx = header.indexOf("extra")
                val dateIdx = TripCsv.Columns.date(header).takeIf { it >= 0 } ?: 0
                val latIdx = TripCsv.Columns.latitude(header).takeIf { it >= 0 } ?: 6
                val lonIdx = TripCsv.Columns.longitude(header).takeIf { it >= 0 } ?: 7
                val mileageIdx = TripCsv.Columns.mileage(header).takeIf { it >= 0 } ?: 8
                var first = true
                // Stay streaming (one big CSV never fully resident), but share
                // the timestamp + great-circle logic with the import/detail
                // paths via TripCsv so every surface agrees on duration/distance.
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEachLine
                    val parts = line.split(",")
                    if (parts.size < 2) return@forEachLine
                    if (tripName == null && extraIdx >= 0) {
                        val cell = parts.getOrNull(extraIdx)?.trim().orEmpty()
                        if (cell.startsWith("trip.name=", ignoreCase = true)) {
                            tripName = cell.substringAfter('=').trim().take(60)
                                .takeIf { it.isNotEmpty() }
                        }
                    }
                    TripCsv.parseDate(parts.getOrNull(dateIdx)?.trim())?.let { t ->
                        if (first) { startTime = t; first = false }
                        endTime = t
                    }
                    val lat = parts.getOrNull(latIdx)?.toDoubleOrNull()
                    val lon = parts.getOrNull(lonIdx)?.toDoubleOrNull()
                    if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                        if (!lastLat.isNaN() && !lastLon.isNaN()) {
                            val d = TripCsv.haversineMeters(lastLat, lastLon, lat, lon)
                            if (d in 0.5..200.0) gpsDistanceKm += d / 1000.0
                        }
                        lastLat = lat
                        lastLon = lon
                    }
                    val mileage = parts.getOrNull(mileageIdx)?.toFloatOrNull()
                    if (mileage != null && mileage > 0f) {
                        if (mileage < minMileage) minMileage = mileage
                        if (mileage > maxMileage) maxMileage = mileage
                    }
                }
            }
        } catch (_: Exception) {}
        val distance = when {
            gpsDistanceKm > 0.0 -> gpsDistanceKm.toFloat()
            minMileage != Float.MAX_VALUE && maxMileage > minMileage -> maxMileage - minMileage
            else -> 0f
        }
        return CsvMeta(startTime, endTime, distance, tripName)
    }

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
            put("alarms", alarmsToJson(alarmDao.getAll()))
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

    /** Outcome of [ensureRiderIdFile]. */
    enum class RiderFileResult {
        /** No folder or no registered rider — nothing to do. */
        SKIPPED,
        /** The file already holds this rider's id. */
        ALREADY_PRESENT,
        /** The file was absent and has now been written with this rider's id. */
        WROTE,
        /** A DIFFERENT rider's id is in the file; left untouched so the caller can warn. */
        MISMATCH,
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
        return try {
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return false
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            // Keep the device's live Dropbox link/sync state -- a backup must
            // never swap in a (possibly stale or blank) token. fromJson now
            // reads these from the JSON like any other field, so re-apply the
            // current values here.
            val restored = SettingsJson.fromJson(json, current).copy(
                dropboxAccessToken = current.dropboxAccessToken,
                dropboxRefreshToken = current.dropboxRefreshToken,
                dropboxAccessTokenExpiresAt = current.dropboxAccessTokenExpiresAt,
                dropboxAccountLabel = current.dropboxAccountLabel,
                dropboxLastSyncAt = current.dropboxLastSyncAt,
            )
            settingsRepository.update(restored)
            applyRestoredLanguage(restored.language)
            // Replace alarm rules wholesale only if the backup contains an
            // "alarms" array. Older backups (pre-v0.4.3) keep the user's
            // current rules untouched.
            if (json.has("alarms")) {
                val rules = jsonToAlarms(json.optJSONArray("alarms"))
                alarmDao.deleteAll()
                rules.forEach { alarmDao.insert(it.copy(id = 0)) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings restore failed", e)
            false
        }
    }

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
     * none). Needs no sync folder — it's a purely local reset.
     */
    suspend fun restoreFactoryDefaults(): Boolean {
        val current = settingsRepository.get()
        return try {
            val factoryJson = SettingsJson.toJson(SettingsJson.stripDeviceBindings(AppSettings()))
            // Factory reset keeps device bindings (pairings, sync folder) and
            // the live Dropbox link, same as the restore path above.
            val reset = SettingsJson.fromJson(factoryJson, current).copy(
                dropboxAccessToken = current.dropboxAccessToken,
                dropboxRefreshToken = current.dropboxRefreshToken,
                dropboxAccessTokenExpiresAt = current.dropboxAccessTokenExpiresAt,
                dropboxAccountLabel = current.dropboxAccountLabel,
                dropboxLastSyncAt = current.dropboxLastSyncAt,
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
    fun enqueueEucStatsUpload(settings: AppSettings) {
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
    fun reconcilePendingTripUploads() = scheduleTripUploadAttempt(attempt = 0)

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
     *  parked on the 32m / 1h step would still fire later and find the
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

    /** Initial enqueue of the Dropbox sync worker. Retries reschedule
     *  themselves via [scheduleDropboxSyncAttempt]. Caller should check
     *  the linked state before calling — this is unconditional. */
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
     * Foreground "Sync all" for Dropbox. Mirrors [startSync] but talks to
     * Dropbox: compare local trips dir against /trips/, prompt the rider
     * on shared file names with the same conflict dialog the SAF sync uses,
     * then upload / download to reconcile. Runs in the app-scoped coroutine
     * so leaving Settings does NOT cancel a half-finished reconcile.
     */
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
                // A manual sync is starting: mark trips as pending so the
                // persistent indicator shows until this pass (or the background
                // worker) clears it. runDropboxSync flips it back on a clean pass.
                settingsRepository.update { it.copy(dropboxSyncPending = true) }
                runDropboxSync()
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

    private suspend fun runDropboxSync() {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        dropboxRepository.clearRateLimited()
        val remote = dropboxRepository.listFolder("/trips")
        if (remote == null) {
            // Auth or network failed: same UX as "no folder" since the rider
            // can't act on the sync until they re-link or reconnect.
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val tripsDir = getTripsDir()
        val localFiles = tripsDir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.toList().orEmpty()
        val localByLower = localFiles.associateBy { it.name.lowercase() }
        val remoteByLower = remote.keys.associateBy { it.lowercase() }
        val remoteMetaByLower = remote.entries.associate { it.key.lowercase() to it.value }

        // A same-name file is a real conflict only when its CONTENT differs (byte
        // size). Same name + same size means it is already synced, so it is
        // neither a conflict nor work. Previously every already-synced trip was
        // flagged as a conflict, so the dialog popped for nothing and "Skip all"
        // still left genuinely new/changed trips to upload - hence "0 trips" yet
        // one still synced. This now matches the background worker's size check.
        val bothNames = remoteByLower.keys intersect localByLower.keys
        val conflictKeys = bothNames.filterTo(HashSet()) { key ->
            (localByLower[key]?.length() ?: -1L) != (remoteMetaByLower[key]?.size ?: -2L)
        }
        val remoteOnly = remoteByLower.keys - localByLower.keys
        val localOnly = localByLower.keys - remoteByLower.keys

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.DROPBOX
            _syncConflictPrompt.value = conflictKeys.size
            choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            if (choice == SyncChoice.CANCEL) {
                // Rider aborted at the conflict prompt: clear the pending flag +
                // count so the persistent indicator disappears instead of popping
                // back up as if the sync were still going.
                settingsRepository.update {
                    it.copy(dropboxSyncPending = false, dropboxPendingCount = 0, dropboxSyncTotal = 0)
                }
                return
            }
        }

        val toUpload = mutableListOf<java.io.File>()
        val toDownload = mutableListOf<String>()
        localOnly.forEach { key -> localByLower[key]?.let { toUpload += it } }
        remoteOnly.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
        when (choice) {
            SyncChoice.APP -> conflictKeys.forEach { key -> localByLower[key]?.let { toUpload += it } }
            SyncChoice.FOLDER -> conflictKeys.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        val total = toUpload.size + toDownload.size

        var done = 0
        // Count files that could NOT be transferred. The most common cause is the
        // OS cutting the app's network the moment it leaves the foreground: the
        // Dropbox host stops resolving and every upload throws. Previously the
        // result was ignored, so a half-skipped sync still reported "Finished".
        var failed = 0
        // Only show the determinate "X of Y" bar when there are trips to move.
        // When total is 0 (everything already backed up) it stays on the
        // indeterminate "Checking" state while settings/themes mirror, instead of
        // a meaningless "0 of 0".
        if (total > 0) _syncProgress.value = done to total
        // Seed the pending-count indicator with the trips to upload, then
        // decrement live as each one lands so the count reflects trips remaining.
        settingsRepository.update {
            it.copy(dropboxPendingCount = toUpload.size, dropboxSyncTotal = toUpload.size)
        }

        for (file in toUpload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            if (dropboxRepository.uploadFile("/trips/${file.name}", file.readBytes())) {
                settingsRepository.update {
                    it.copy(dropboxPendingCount = (it.dropboxPendingCount - 1).coerceAtLeast(0))
                }
            } else failed++
            done++
            _syncProgress.value = done to total
        }

        for (name in toDownload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            val bytes = dropboxRepository.downloadFile("/trips/$name")
            if (bytes == null) failed++
            if (bytes != null) {
                val dest = File(tripsDir, name)
                dest.outputStream().use { it.write(bytes) }
                // Carry Dropbox's timestamp on to the file. Without it every
                // trip just pulled down looks like it was written on this phone
                // a moment ago, and the next pass sends the whole library
                // straight back up.
                remoteMetaByLower[name.lowercase()]?.let {
                    dest.setLastModified(it.serverModifiedSec * 1000L)
                }
                // Mirror the SAF path: if the file is not yet known to Room,
                // insert a row so it shows up in the trips list.
                val existing = tripDao.findByFileName(name)
                val meta = parseCsvMeta(dest)
                if (existing == null) {
                    tripDao.insert(TripRecord(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        fileName = name,
                        distanceKm = meta.distanceKm,
                        // The file carries the rider's name for it; a downloaded
                        // trip used to arrive nameless and show its date instead.
                        customName = meta.name,
                        // Mirror it into the backup folder, but only where the
                        // folder has nothing by that name: a download is not the
                        // authority on a file the rider already has there.
                        uploadStatus = if (settings.syncFolderUri != null) 4 else 0,
                    ))
                } else {
                    // The rider reached this file by answering "keep Dropbox's
                    // copy" at the conflict prompt, and the bytes on disk have
                    // just been replaced. The row has to follow, or the list
                    // goes on showing what the old copy said - which is how a
                    // rename made in another tool arrived on the phone and was
                    // still displayed under its old name.
                    tripDao.update(existing.copy(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        distanceKm = meta.distanceKm,
                        // A copy with no name in it does not erase one set here.
                        customName = meta.name ?: existing.customName,
                        // The backup folder now holds the copy the rider chose
                        // against, so send this one over it.
                        uploadStatus = if (settings.syncFolderUri != null) 1 else existing.uploadStatus,
                    ))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        // Refresh settings.json and mirror the rest of the backup folder
        // (themes, overlays) so an explicit "Sync all" pushes the WHOLE folder,
        // not just trips. Missing/newer only -- same per-file rule as trips, no
        // extra conflict prompt.
        var extra = 0
        if (dropboxRepository.uploadFile(
                "/settings.json",
                SettingsJson.toJson(settings).toString().toByteArray(Charsets.UTF_8)
            )
        ) extra++
        extra += mirrorBackupSubdirsToDropbox(settings)

        if (failed == 0) {
            // Only stamp the sync time on a clean pass, so a partial run stays
            // detectable and the next comparison re-uploads what it missed.
            // Clear the pending flag: everything landed, so the persistent
            // "Syncing trips…" indicator disappears.
            settingsRepository.update {
                it.copy(
                    dropboxLastSyncAt = System.currentTimeMillis(),
                    dropboxSyncPending = false,
                    dropboxPendingCount = 0,
                    dropboxSyncTotal = 0,
                )
            }
            // "Synchronized N trips" counts TRIPS only, not the settings.json /
            // themes / overlays mirror (extra) - counting those made an all-synced
            // pass that merely refreshed settings report "1 trip". When no trip
            // moved, say "already up to date" instead of "0 trips".
            _syncResult.value =
                if (total == 0) SyncResult.UpToDate else SyncResult.Finished(total)
            if (settings.syncFolderUri != null) enqueueTripUpload(settings)
            settingsRepository.update { it.copy(dropboxPullRequested = false) }
        } else if (dropboxRepository.refused) {
            // Dropbox has cut us off rather than asked us to wait. Coming back
            // in a minute, as an ordinary failure would, is what keeps us cut
            // off - so wait properly. The rider is told the same thing either
            // way: Dropbox is limiting us and their trips are not lost.
            _syncResult.value = SyncResult.RateLimited(total - failed, total)
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            scheduleDropboxSyncAttempt(0, delaySeconds = REFUSED_RETRY_SECONDS)
        } else if (dropboxRepository.rateLimited) {
            // Say which it was. The retry worker still picks the rest up, but
            // the rider is owed the reason their library stopped halfway.
            _syncResult.value = SyncResult.RateLimited(total - failed, total)
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            scheduleDropboxSyncAttempt(0)
        } else {
            // Hand the skipped trips to the retry worker (missing/newer only), so
            // they upload once the network is back instead of being silently lost.
            // No toast: the persistent pending flag + count + indicator cover it,
            // and the worker keeps retrying until every trip lands or the rider
            // cancels.
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            scheduleDropboxSyncAttempt(0)
        }
    }

    /** Upload the backup folder's themes/ and overlays/ files to Dropbox
     *  (missing or newer only). Returns how many were uploaded. */
    private suspend fun mirrorBackupSubdirsToDropbox(settings: AppSettings): Int {
        val folder = getSyncFolder(settings) ?: return 0
        var count = 0
        for (sub in listOf("themes", "overlays")) {
            // Wrap each subfolder: if the folder URI is revoked mid-sync (the UI
            // disables Change/Remove folder while syncing, but be defensive) the
            // SAF reads throw -- swallow rather than crash the whole sync.
            try {
                val subDir = folder.findFile(sub)?.takeIf { it.isDirectory } ?: continue
                val remote = dropboxRepository.listFolder("/$sub") ?: emptyMap()
                for (doc in subDir.listFiles()) {
                    if (!doc.isFile) continue
                    val name = doc.name ?: continue
                    val localMod = doc.lastModified() / 1000L
                    if (remote[name]?.let { it.serverModifiedSec >= localMod } == true) continue
                    val bytes = try {
                        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                    } catch (e: Exception) { null } ?: continue
                    if (dropboxRepository.uploadFile("/$sub/$name", bytes)) count++
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "mirror /$sub failed: ${e.message}")
            }
        }
        return count
    }

    /**
     * Move a trip's file out of the way on every destination it reached.
     *
     * A trip that was extended into a longer one, or split into pieces, still
     * has its own file sitting in Dropbox and in the backup folder. Leave it
     * there and the same ride is stored twice, and the copy comes back down
     * the next time the rider syncs on another phone. Archiving moves it into
     * an "archive" subfolder on both: out of the listing the sync walks, but
     * still the rider's data.
     *
     * Best effort per destination. Dropbox reports a missing file as done,
     * since a trip that never got there needs no archiving.
     */
    /**
     * Move a trip's file out of the way on every destination that has it.
     *
     * A trip that was extended into a longer one, split into pieces, or that
     * the rider deleted and meant it, still has its own file in Dropbox and in
     * the backup folder. Both syncs treat a file the phone does not have as one
     * to fetch, so leaving it there hands the trip straight back. Archiving
     * moves it into an "archive" subfolder on each: out of the listings the
     * syncs walk, still the rider's data, and undeletable by us.
     *
     * Order is the whole design. Dropbox goes first because it is the step that
     * needs the network and therefore the step that fails, and failing there
     * has touched nothing yet. The backup folder goes second, and if it fails
     * the Dropbox move is put back, so a half-archived trip never exists. The
     * phone's copy goes last of all: while it is here the rider can try again,
     * and deleting it while a backup still holds the file is what produces a
     * trip that keeps coming back.
     *
     * @return true only when the file is archived everywhere it existed
     */
    suspend fun archiveTripFile(fileName: String): Boolean {
        val settings = settingsRepository.get()
        val moved = if (settings.dropboxAccessToken.isNotBlank()) {
            dropboxRepository.moveFile("/trips/$fileName", "/trips/archive/$fileName")
        } else null
        val folderOk = moved !is MoveOutcome.Failed && archiveInBackupFolder(settings, fileName)
        val decision = ArchivePolicy.decide(moved, folderOk)
        if (decision.rollback && moved is MoveOutcome.Moved) {
            // Best effort: if putting it back also fails, the phone still has
            // the trip and the next sync re-uploads it, so the rider loses
            // nothing either way.
            dropboxRepository.moveFile(moved.toPath, "/trips/$fileName")
        }
        return decision.archived
    }

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
     * Archive many trips at once, for "delete all".
     *
     * Same rule as the single-file path - Dropbox first, folder second, and
     * the caller only drops its phone copies for the names that come back -
     * but in batches, because a rider with two thousand trips would otherwise
     * wait out two thousand round trips.
     *
     * All or nothing per destination rather than per file: a batch either goes
     * through or it does not, and a partly-moved batch is put back the same
     * way the single move is.
     *
     * @return the file names that are now archived everywhere they existed
     */
    suspend fun archiveTripFiles(fileNames: List<String>): Set<String> {
        if (fileNames.isEmpty()) return emptySet()
        if (fileNames.size == 1) {
            return if (archiveTripFile(fileNames.first())) fileNames.toSet() else emptySet()
        }
        val settings = settingsRepository.get()
        val dropboxLinked = settings.dropboxAccessToken.isNotBlank()
        if (dropboxLinked) {
            val pairs = fileNames.map { "/trips/$it" to "/trips/archive/$it" }
            if (!dropboxRepository.moveFilesBatch(pairs)) {
                // A batch that reports failure may still have moved some of it:
                // the job runs on Dropbox's side and the answer can be lost on
                // the way back. Ask for the reverse before giving up, so the
                // two sides cannot be left disagreeing. Entries that never
                // moved simply are not found, which the batch tolerates.
                dropboxRepository.moveFilesBatch(
                    fileNames.map { "/trips/archive/$it" to "/trips/$it" }
                )
                return emptySet()
            }
        }
        val accepted = fileNames.filterTo(HashSet()) { archiveInBackupFolder(settings, it) }
        val (done, putBack) = ArchivePolicy.decideBatch(fileNames, dropboxOk = true, folderAccepted = accepted)
        if (dropboxLinked && putBack.isNotEmpty()) {
            // Whatever the folder would not take goes back to /trips, so the
            // two sides never disagree about where a trip lives.
            dropboxRepository.moveFilesBatch(putBack.map { "/trips/archive/$it" to "/trips/$it" })
        }
        return done
    }

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
            val doc = tripsDir.findFile(fileName)?.takeIf { it.isFile } ?: return true
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
    fun listFolderTripNames(settings: AppSettings): List<String>? {
        val root = getSyncFolder(settings) ?: return null
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return emptyList()
        return trips.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            if (doc.isFile && name.endsWith(".csv", ignoreCase = true)) name else null
        }
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
         * not have - then costs nothing.
         */
        knownNames: Set<String>? = null,
    ): Boolean {
        val root = getSyncFolder(settings) ?: return false
        val tripsFolder = root.findFile(TRIPS_SUBFOLDER)
            ?: root.createDirectory(TRIPS_SUBFOLDER)
            ?: return false
        return try {
            val knownHas = knownNames?.contains(localFile.name)
            // Nothing to do, and the caller's listing already proves it. Going
            // to the folder to confirm costs a full directory listing, which is
            // the whole reason mirroring a restored library crawled: most of
            // those trips are already backed up.
            if (knownHas == true && skipIfPresent) return true
            val existing = if (knownHas == false) null else tripsFolder.findFile(localFile.name)
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


    private fun alarmsToJson(rules: List<AlarmRule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("name", r.name)
                put("enabled", r.enabled)
                put("sortOrder", r.sortOrder)
                put("metric", r.metric)
                put("comparator", r.comparator)
                put("threshold", r.threshold.toDouble())
                put("beepEnabled", r.beepEnabled)
                put("beepFrequency", r.beepFrequency)
                put("beepDurationMs", r.beepDurationMs)
                put("beepCount", r.beepCount)
                put("beepModulation", r.beepModulation)
                put("beepGapMs", r.beepGapMs)
                put("beepVolume", r.beepVolume)
                put("beepVolumeModulation", r.beepVolumeModulation)
                put("beepModulationReachPct", r.beepModulationReachPct)
                put("beepVolumeReachPct", r.beepVolumeReachPct)
                put("voiceEnabled", r.voiceEnabled)
                put("voiceText", r.voiceText)
                put("vibrateEnabled", r.vibrateEnabled)
                put("vibrateDurationMs", r.vibrateDurationMs)
                put("vibrateTarget", r.vibrateTarget)
                put("cooldownSeconds", r.cooldownSeconds)
                put("repeatWhileActive", r.repeatWhileActive)
                put("leadTimeMs", r.leadTimeMs)
            })
        }
    }

    private fun jsonToAlarms(arr: JSONArray?): List<AlarmRule> {
        if (arr == null) return emptyList()
        val out = mutableListOf<AlarmRule>()
        val default = AlarmRule()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AlarmRule(
                name = o.optString("name", default.name),
                enabled = o.optBoolean("enabled", default.enabled),
                sortOrder = o.optInt("sortOrder", default.sortOrder),
                metric = o.optString("metric", default.metric),
                comparator = o.optString("comparator", default.comparator),
                threshold = o.optDouble("threshold", default.threshold.toDouble()).toFloat(),
                beepEnabled = o.optBoolean("beepEnabled", default.beepEnabled),
                beepFrequency = o.optInt("beepFrequency", default.beepFrequency),
                beepDurationMs = o.optInt("beepDurationMs", default.beepDurationMs),
                beepCount = o.optInt("beepCount", default.beepCount),
                beepModulation = o.optInt("beepModulation", default.beepModulation),
                beepGapMs = o.optInt("beepGapMs", default.beepGapMs),
                beepVolume = o.optInt("beepVolume", default.beepVolume),
                beepVolumeModulation = o.optInt("beepVolumeModulation", default.beepVolumeModulation),
                beepModulationReachPct = o.optInt("beepModulationReachPct", default.beepModulationReachPct),
                beepVolumeReachPct = o.optInt("beepVolumeReachPct", default.beepVolumeReachPct),
                voiceEnabled = o.optBoolean("voiceEnabled", default.voiceEnabled),
                voiceText = o.optString("voiceText", default.voiceText),
                vibrateEnabled = o.optBoolean("vibrateEnabled", default.vibrateEnabled),
                vibrateDurationMs = o.optInt("vibrateDurationMs", default.vibrateDurationMs),
                vibrateTarget = o.optString("vibrateTarget", default.vibrateTarget),
                cooldownSeconds = o.optInt("cooldownSeconds", default.cooldownSeconds),
                repeatWhileActive = o.optBoolean("repeatWhileActive", default.repeatWhileActive),
                leadTimeMs = o.optInt("leadTimeMs", default.leadTimeMs)
            )
        }
        return out
    }
}

/**
 * Result of a settings-backup attempt. [AlreadyExists] is only ever returned
 * when the caller asked not to overwrite.
 */
sealed interface BackupOutcome {
    data object Saved : BackupOutcome
    data object AlreadyExists : BackupOutcome
    data object Failed : BackupOutcome
}

/**
 * One row in the restore picker. [label] = null is the default backup
 * (`eucplanet_settings.json`); non-null is the rider-supplied snapshot name.
 */
data class BackupEntry(val fileName: String, val label: String?, val isFactory: Boolean = false)

/**
 * The rider identity carried by a settings backup, read without applying it.
 * Used to offer "restore your existing rider" when a sync folder is linked.
 */
data class RestorableRider(val fileName: String, val storeId: String)
