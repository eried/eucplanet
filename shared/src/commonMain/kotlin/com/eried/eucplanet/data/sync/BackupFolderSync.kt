package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.AlarmBackupJson
import com.eried.eucplanet.data.store.SettingsJson
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.formatLocalDateUs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The platform side of [BackupFolderSync]: Android's WorkManager jobs, the iPhone's in-app queue. */
interface BackupFolderHost {
    /**
     * Queue the folder upload pass ([BackupFolderSync.uploadPass]) for
     * [attempt]: 0 runs at once, higher attempts wait
     * [DropboxTripSync.delayForAttempt]. Replaces a pass already queued, so a
     * fresh enqueue resets the retry chain.
     */
    fun scheduleUpload(attempt: Int)

    /** Drop the queued folder upload pass, if any. */
    fun cancelUpload()
}

/**
 * The backup folder the rider picked, the same on both phones: the trips in
 * trips/ (deleted ones in trips/archive/), the settings backups and their
 * named copies, the leaderboard rider id file, and the themes/ and overlays/
 * folders synced with Dropbox. Android's SyncManager and the iPhone run these
 * passes over their own [BackupFolder], so one folder can be shared by an
 * Android phone and an iPhone: every name and rule is in here.
 *
 * - [runSync]: the rider's Sync all for the folder. Asks once about real
 *   conflicts, then copies both ways.
 * - [uploadPass]: the quiet mirror (Android's TripUploadWorker).
 * - Settings: [backupSettingsAs], [listSettingsBackups], [restoreSettingsFrom].
 * - The rider id: [writeRiderIdFile], [readRiderIdFile], [ensureRiderIdFile].
 */
class BackupFolderSync(
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val dropboxRepository: DropboxRepository,
    private val dropboxSettings: DropboxSettingsSync,
    private val alarms: AlarmRuleStore,
    private val tripFiles: TripFileStore,
    private val access: BackupFolderAccess,
    private val host: BackupFolderHost,
) {
    companion object {
        // Logs keep the tags they had inside SyncManager and TripUploadWorker.
        private const val TAG = "SyncManager"
        private const val WORKER_TAG = "TripUploadWorker"
        private const val DROPBOX_WORKER_TAG = "DropboxSyncWorker"

        const val SETTINGS_BACKUP_NAME = "eucplanet_settings.json"
        const val SETTINGS_BACKUP_PREFIX = "eucplanet_settings-"
        const val SETTINGS_BACKUP_SUFFIX = ".json"

        // Plain-text file holding ONLY the eucstats online rider id (the
        // store_id UUID, nothing else) -- the recovery token for "found a
        // previous profile" after a reinstall / new device. Name/flag/stats all
        // come from the server, so the id is the entire identity.
        const val RIDER_BACKUP_NAME = "eucstats_riderid.txt"
        const val TRIPS_SUBFOLDER = "trips"
        const val ARCHIVE_SUBFOLDER = "archive"
        const val THEMES_SUBFOLDER = "themes"
        const val OVERLAYS_SUBFOLDER = "overlays"

        /** The JSON folders that sync with Dropbox's folders of the same name. */
        val JSON_SUBFOLDERS = listOf(THEMES_SUBFOLDER, OVERLAYS_SUBFOLDER)

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

        fun buildBackupFileName(name: String?): String =
            if (name == null) SETTINGS_BACKUP_NAME else "$SETTINGS_BACKUP_PREFIX$name$SETTINGS_BACKUP_SUFFIX"

        /**
         * A name nothing in [taken] is using yet: "trip.csv", then "trip (1).csv".
         *
         * The same shape Dropbox's autorename produces, so a rider looking at the
         * two archives sees the same thing in both.
         */
        fun freeArchiveName(taken: Set<String>, fileName: String): String {
            if (fileName !in taken) return fileName
            val stem = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            var n = 1
            while (n < 1000) {
                val candidate = "$stem ($n)$suffix"
                if (candidate !in taken) return candidate
                n++
            }
            return fileName
        }
    }

    // --- the folder ---------------------------------------------------------

    /** The chosen folder, or null if none or no longer reachable. */
    fun folder(settings: AppSettings): BackupFolder? {
        val uri = settings.syncFolderUri ?: return null
        return try {
            access.open(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open sync folder", e)
            null
        }
    }

    /** Where the folder is, as Settings shows it; null with no reachable folder. */
    fun displayName(settings: AppSettings): String? = folder(settings)?.displayName

    /** The entry called exactly [name] in [dir], file or folder. */
    private fun BackupFolder.find(dir: String, name: String): BackupFolderEntry? =
        list(dir)?.firstOrNull { it.name == name }

    /**
     * The rest of picking a folder, once the platform has its permission:
     * save it, and count its conflicts (the count belonged to the folder
     * before this one).
     */
    suspend fun useFolder(uri: String) {
        val current = settingsRepository.get()
        settingsRepository.update(current.copy(syncFolderUri = uri))
        refreshFolderConflictCount(settingsRepository.get())
    }

    /**
     * The rest of removing the folder, once the platform has let its
     * permission go. [riderIdInFolder]: the leaderboard rider id lives only in
     * the folder (Android), so uploading to the leaderboard stops with it.
     */
    suspend fun forgetFolder(riderIdInFolder: Boolean) {
        val s = settingsRepository.get()
        settingsRepository.update(s.copy(
            syncFolderUri = null,
            // online upload requires a folder where the rider id lives in one
            onlineUploadEnabled = if (riderIdInFolder) false else s.onlineUploadEnabled,
            folderConflictCount = 0,      // no folder, nothing to differ from
        ))
        // The folder is gone: drop a pending retry so we don't sit on a
        // backed-off pass that would only no-op when it finally fires.
        host.cancelUpload()
    }

    // --- settings backups ---------------------------------------------------

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
    suspend fun backupSettingsAs(name: String?, overwrite: Boolean): BackupOutcome = withContext(Dispatchers.IO) {
        val current = settingsRepository.get()
        val folder = folder(current) ?: return@withContext BackupOutcome.Failed
        val fileName = buildBackupFileName(name)
        val existing = folder.find("", fileName)
        if (existing != null && !overwrite) return@withContext BackupOutcome.AlreadyExists
        val payload = SettingsJson.toJson(SettingsJson.stripDeviceBindings(current)).apply {
            put("alarms", AlarmBackupJson.alarmsToJson(alarms.getAll()))
        }
        // The file says when it was written; the app keeps no copy of that.
        val json = BackupStamp.stamp(payload.toString(2), System.currentTimeMillis())
        try {
            if (existing != null) folder.delete(fileName)
            if (folder.write(fileName, json.encodeToByteArray())) BackupOutcome.Saved else BackupOutcome.Failed
        } catch (e: Exception) {
            Log.e(TAG, "Settings backup failed", e)
            BackupOutcome.Failed
        }
    }

    /** Restore from the named backup file in the sync folder. */
    suspend fun restoreSettingsFrom(fileName: String): Boolean {
        val current = settingsRepository.get()
        val bytes = withContext(Dispatchers.IO) {
            val folder = folder(current) ?: return@withContext null
            folder.find("", fileName) ?: return@withContext null
            folder.read(fileName)
        } ?: return false
        return dropboxSettings.applySettingsFromJson(bytes)
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
            // Factory reset keeps device bindings (pairings, sync folder) and
            // the live Dropbox link, same as the restore path above.
            settingsRepository.update(SettingsJson.factoryReset(current))
            alarms.replaceAll(emptyList())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed", e)
            false
        }
    }

    /**
     * List every settings backup in the sync folder. The default
     * `eucplanet_settings.json` is always returned first (with [BackupEntry.label]
     * = null), followed by named snapshots sorted by display label. Each
     * carries when it was written, read from the file.
     */
    suspend fun listSettingsBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val current = settingsRepository.get()
        val folder = folder(current) ?: return@withContext emptyList()
        val out = mutableListOf<BackupEntry>()
        val named = mutableListOf<BackupEntry>()
        folder.list().orEmpty().forEach { doc ->
            val n = doc.name
            if (!n.endsWith(".json", ignoreCase = true)) return@forEach
            when {
                n.equals(SETTINGS_BACKUP_NAME, ignoreCase = true) -> {
                    out += BackupEntry(fileName = n, label = null, writtenAt = writtenAt(folder, doc))
                }
                n.startsWith(SETTINGS_BACKUP_PREFIX, ignoreCase = true) &&
                    n.length > SETTINGS_BACKUP_PREFIX.length + SETTINGS_BACKUP_SUFFIX.length -> {
                    val label = n.substring(
                        SETTINGS_BACKUP_PREFIX.length,
                        n.length - SETTINGS_BACKUP_SUFFIX.length
                    )
                    if (label.isNotEmpty()) {
                        named += BackupEntry(fileName = n, label = label, writtenAt = writtenAt(folder, doc))
                    }
                }
            }
        }
        named.sortBy { it.label?.lowercase() }
        out + named
    }

    /** When a backup file was written: its own stamp, or for a file from
     *  before backups carried one, the time the folder has for it. */
    private fun writtenAt(folder: BackupFolder, doc: BackupFolderEntry): Long? {
        val stamp = runCatching {
            folder.readHead(doc.name, BackupStamp.HEAD_BYTES)
                ?.takeIf { it.isNotEmpty() }
                ?.let { BackupStamp.read(it.decodeToString()) }
        }.getOrNull()
        return stamp ?: doc.lastModified.takeIf { it > 0L }
    }

    /**
     * Save a timestamped safety copy of the CURRENT settings before a restore
     * that would replace the rider identity, so the previous rider stays
     * recoverable even if the rider taps through the confirm. Best-effort.
     */
    suspend fun snapshotBeforeRestore(): Boolean {
        val stamp = formatLocalDateUs("yyyyMMdd-HHmm", System.currentTimeMillis())
        return backupSettingsAs(name = "before-restore-$stamp", overwrite = false) == BackupOutcome.Saved
    }

    // --- the leaderboard rider id ------------------------------------------

    /**
     * Write a rider's store_id to the recovery file ([RIDER_BACKUP_NAME]) in
     * the sync folder. This is the registration / restore path's persistence
     * step. Returns true on success; false if there's no folder or the write
     * failed.
     */
    suspend fun writeRiderIdFile(storeId: String): Boolean = withContext(Dispatchers.IO) {
        val folder = folder(settingsRepository.get()) ?: return@withContext false
        try {
            if (folder.find("", RIDER_BACKUP_NAME) != null) folder.delete(RIDER_BACKUP_NAME)
            folder.write(RIDER_BACKUP_NAME, storeId.encodeToByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Rider id write failed", e)
            false
        }
    }

    /** Delete the recovery file ([RIDER_BACKUP_NAME]). True if a file was
     *  deleted, false if none was, null when there is no folder at all. */
    suspend fun deleteRiderIdFile(): Boolean? = withContext(Dispatchers.IO) {
        val folder = folder(settingsRepository.get()) ?: return@withContext null
        if (folder.find("", RIDER_BACKUP_NAME) != null) folder.delete(RIDER_BACKUP_NAME) else false
    }

    /** Read the plain-text online rider id (store_id) from RIDER_BACKUP_NAME, or null. */
    suspend fun readRiderIdFile(): String? = withContext(Dispatchers.IO) {
        val current = settingsRepository.get()
        val folder = folder(current) ?: return@withContext null
        folder.find("", RIDER_BACKUP_NAME) ?: return@withContext null
        try {
            folder.read(RIDER_BACKUP_NAME)?.decodeToString()?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read rider id file", e)
            null
        }
    }

    /**
     * Ensure the recovery file holds [storeId]. Used at registration / link
     * time so the rider's identity is hardened in the sync folder before
     * anything else can clobber it. Leaves a foreign rider's file untouched
     * and reports [RiderFileResult.MISMATCH] so the caller can warn. [write]
     * is the platform's own write, which also tells its listeners.
     */
    suspend fun ensureRiderIdFile(
        storeId: String,
        write: suspend (String) -> Boolean = { writeRiderIdFile(it) },
    ): RiderFileResult {
        folder(settingsRepository.get()) ?: return RiderFileResult.SKIPPED
        return when (readRiderIdFile()) {
            storeId -> RiderFileResult.ALREADY_PRESENT
            null -> if (write(storeId)) RiderFileResult.WROTE else RiderFileResult.SKIPPED
            else -> RiderFileResult.MISMATCH
        }
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

    // --- trips: the conflict count ------------------------------------------

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
        val localSizes = HashMap<String, Long>()
        for (trip in tripDao.observeAll().first()) {
            if (trip.endTime == null) continue
            val file = tripFiles.file(trip.fileName)
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

    // --- trips: Sync all ----------------------------------------------------

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
    private val uploadPassLock = Mutex()

    suspend fun <T> withUploadPass(block: suspend () -> T): T =
        uploadPassLock.withLock { block() }

    /**
     * The rider's Sync all for the folder. Takes the same pass lock as the
     * upload worker.
     *
     * Both write into the trips folder, and the app runs this on launch, which
     * is exactly when a worker mirroring a restored library is busy. Two of
     * them creating one file does not fail: the document provider renames the
     * loser, leaving "trip (1) (1).csv" beside the backup. Seen happening.
     */
    suspend fun runSync(ui: DropboxSyncUi) = withUploadPass {
        try {
            runSyncPass(ui)
        } finally {
            // The rider just picked a side, or skipped, or cancelled: the
            // warning must say what the folder holds now, not what the worker
            // saw on its last pass. Counted even after a cancel, which is why
            // it cannot itself be cancelled.
            withContext(NonCancellable) {
                val settings = settingsRepository.get()
                if (settings.syncFolderUri != null) refreshFolderConflictCount(settings)
            }
        }
    }

    private suspend fun runSyncPass(ui: DropboxSyncUi) = withContext(Dispatchers.IO) {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            ui.result(SyncResult.NoFolder)
            return@withContext
        }
        val folderSizes = listFolderTripSizes(settings)
        if (folderSizes == null) {
            ui.result(SyncResult.NoFolder)
            return@withContext
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
        val conflictKeys = (folderByLower.keys intersect dbByLower.keys).filterTo(HashSet()) { key ->
            val local = tripFiles.file(dbByLower.getValue(key).fileName)
            local.exists() && local.length() != folderSizeByLower[key]
        }
        val folderOnlyKeys = folderByLower.keys - dbByLower.keys
        val dbOnly = dbTrips.filter {
            it.endTime != null && it.fileName.lowercase() !in folderByLower.keys
        }

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            choice = ui.askConflict(conflictKeys.size, firstSettings = false)
            if (choice == SyncChoice.CANCEL) return@withContext
        }

        val toUpload = dbOnly.toMutableList()
        val toDownload = folderOnlyKeys.mapNotNull { folderByLower[it] }.toMutableList()
        // Known trip, file gone from the phone: the folder's copy is the only one.
        (folderByLower.keys intersect dbByLower.keys)
            .filter { !tripFiles.file(dbByLower.getValue(it).fileName).exists() }
            .mapNotNullTo(toDownload) { folderByLower[it] }
        when (choice) {
            SyncChoice.APP -> toUpload.addAll(conflictKeys.mapNotNull { dbByLower[it] })
            SyncChoice.FOLDER -> toDownload.addAll(conflictKeys.mapNotNull { folderByLower[it] })
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        val total = toUpload.size + toDownload.size
        if (total == 0) {
            ui.result(SyncResult.UpToDate)
            return@withContext
        }

        var done = 0
        // Uploads that failed this pass (folder access lost, storage full). They
        // are marked uploadStatus=3 and retried by the folder-backup worker.
        var failed = 0
        ui.progress(done, total)

        for (trip in toUpload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            val file = tripFiles.file(trip.fileName)
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
            ui.progress(done, total)
        }

        for (fileName in toDownload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            if (downloadCsv(settings, fileName)) {
                val meta = TripCsvMeta.read(tripFiles.file(fileName))
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
            ui.progress(done, total)
        }

        // Failed uploads are uploadStatus=3, which the folder-backup worker
        // retries, so kick it so they keep retrying in the background.
        // The result is always Finished -- no error toast; failed folder uploads
        // finish silently once conditions recover.
        if (failed > 0) enqueueUpload(settings)
        ui.result(SyncResult.Finished(total))
    }

    // --- trips: the quiet mirror --------------------------------------------

    /** Queue the folder upload pass now, when there is a folder to upload to. */
    fun enqueueUpload(settings: AppSettings) {
        if (settings.syncFolderUri == null) return
        host.scheduleUpload(0)
    }

    /**
     * Uploads any trip CSVs that haven't been synced yet, newest-first: the
     * body of Android's TripUploadWorker, the iPhone's folder pass. False when
     * an upload failed, so the platform queues the next attempt up the curve.
     */
    suspend fun uploadPass(): Boolean = withUploadPass {
        withContext(Dispatchers.IO) {
            val settings = settingsRepository.get()
            if (settings.syncFolderUri == null) {
                Log.i(WORKER_TAG, "No sync folder configured, skipping")
                return@withContext true
            }

            val pending = tripDao.getPendingUploads()

            removeRenamedTripCopies(settings)

            // One listing for the whole pass. Asking the folder about each trip in
            // turn is what made mirroring a restored library crawl.
            val folderSizes = listFolderTripSizes(settings)
            // Shared storage ignores case, so every lookup here does too.
            val knownNames = folderSizes?.keys?.associateBy { it.lowercase() }

            // The queue, plus everything the folder is missing. This worker used
            // to walk only its queue while the Dropbox worker compares every
            // local file against the remote listing on every pass - so Dropbox
            // was a mirror and the folder was a mailbox, and trips older than the
            // folder (or files deleted from it behind the app's back) stayed
            // missing forever, surfacing only as the warning in Backups. Same
            // rule as everything else in this pass: skip-if-present, so a copy
            // the folder already holds is never overwritten.
            val reconcile = if (knownNames == null) emptyList() else {
                val queued = pending.map { it.fileName.lowercase() }.toHashSet()
                tripDao.observeAll().first().filter { t ->
                    t.endTime != null && t.fileName.lowercase() !in queued &&
                        t.fileName.lowercase() !in knownNames
                }
            }
            // Conflicts: both sides have the file, with different bytes. The
            // sweep must not touch those - overwriting either direction destroys
            // somebody's copy - so they are counted and told to the dashboard,
            // whose warning sends the rider to the sync conflict dialog. Counted
            // on every pass, and again when a manual sync ends, so fixing them
            // (or deleting a side) clears the warning.
            if (folderSizes != null) refreshFolderConflictCount(settings, folderSizes)

            if (pending.isEmpty() && reconcile.isEmpty()) return@withContext true
            if (reconcile.isNotEmpty()) Log.i(WORKER_TAG, "Reconcile: folder is missing ${reconcile.size} trip(s)")

            var anyFailed = false
            for (trip in pending + reconcile.map { it.copy(uploadStatus = 4) }) {
                val file = tripFiles.file(trip.fileName)
                if (!file.exists()) {
                    // Mark as uploaded anyway so it stops retrying forever
                    tripDao.markUploaded(trip.id, System.currentTimeMillis())
                    continue
                }

                // Status 4 came from Dropbox: mirror it in, but never over a file
                // the folder already holds.
                val ok = uploadCsv(
                    settings, file,
                    skipIfPresent = trip.uploadStatus == 4,
                    knownNames = knownNames,
                )
                if (ok) {
                    tripDao.markUploaded(trip.id, System.currentTimeMillis())
                    Log.i(WORKER_TAG, "Uploaded ${trip.fileName}")
                } else {
                    tripDao.setUploadStatus(trip.id, 3)
                    anyFailed = true
                    Log.w(WORKER_TAG, "Upload failed for ${trip.fileName}")
                }
            }
            !anyFailed
        }
    }

    /**
     * The folder half of pushing one edited trip at once (see SyncManager's
     * pushEditedTripNow). False when the trip's row went away meanwhile, so
     * the caller stops there.
     */
    suspend fun pushEditedTrip(settings: AppSettings, trip: TripRecord): Boolean {
        if (settings.syncFolderUri == null) return true
        val file = tripFiles.file(trip.fileName)
        val ok = runCatching { withUploadPass { withContext(Dispatchers.IO) { uploadCsv(settings, file) } } }
            .getOrDefault(false)
        tripDao.update((tripDao.getById(trip.id) ?: return false).copy(
            uploadStatus = if (ok) 2 else 3,
            uploadedAt = if (ok) System.currentTimeMillis() else trip.uploadedAt,
        ))
        if (!ok) host.scheduleUpload(1)
        return true
    }

    /**
     * List CSV filenames in the trips subfolder. Returns null if the folder
     * is unavailable, empty list if the folder exists but has no trips yet.
     */
    fun listFolderTripNames(settings: AppSettings): List<String>? =
        listFolderTripSizes(settings)?.keys?.toList()

    /** CSV name -> byte size for the trips subfolder, in one directory walk.
     *  Sizes are what tell an already-backed-up trip from a conflict: same
     *  name, different bytes means the two sides disagree and neither may be
     *  overwritten without the rider choosing. */
    fun listFolderTripSizes(settings: AppSettings): Map<String, Long>? {
        val root = folder(settings) ?: return null
        val trips = root.list(TRIPS_SUBFOLDER) ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (doc in trips) {
            val name = doc.name
            if (!doc.isDirectory && name.endsWith(".csv", ignoreCase = true)) out[name] = doc.size
        }
        return out
    }

    /**
     * Delete the renamed duplicates a case mismatch used to leave in the
     * backup folder's trips, see [RenamedCopyCleanup]. Names and sizes come
     * from one listing, and files are only read when a "(N)" copy has a
     * same-sized candidate, so a clean folder costs one listing. Returns how
     * many copies went.
     */
    fun removeRenamedTripCopies(settings: AppSettings): Int {
        val root = folder(settings) ?: return 0
        val listing = try {
            root.list(TRIPS_SUBFOLDER) ?: return 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the trips folder for duplicates", e)
            return 0
        }
        val entries = listing
            .filter { !it.isDirectory && it.name.endsWith(".csv", ignoreCase = true) }
            .map { RenamedCopyCleanup.Entry(it.name, it.size) }
        fun bytes(name: String) = root.read(folderPath(TRIPS_SUBFOLDER, name))
        val doomed = RenamedCopyCleanup.plan(entries) { a, b ->
            val x = bytes(a.name); x != null && x.contentEquals(bytes(b.name))
        }
        var removed = 0
        for (name in doomed) {
            val ok = try {
                root.delete(folderPath(TRIPS_SUBFOLDER, name))
            } catch (e: Exception) { false }
            if (ok) removed++
        }
        if (removed > 0) Log.i(TAG, "Removed $removed duplicate trip copies from the backup folder")
        return removed
    }

    /** Copy a folder CSV into the phone's trips folder as [fileName]. */
    fun downloadCsv(settings: AppSettings, fileName: String): Boolean {
        val root = folder(settings) ?: return false
        val bytes = root.read(folderPath(TRIPS_SUBFOLDER, fileName)) ?: return false
        return try {
            tripFiles.writeWhole(fileName, bytes)
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
        localFile: TripFile,
        /** Leave a file of the same name alone instead of replacing it. Set for
         *  trips that came down from Dropbox: the folder's copy may be a
         *  different version and is not ours to overwrite unasked. */
        skipIfPresent: Boolean = false,
        /**
         * The names the folder already held when the pass started, if the
         * caller has them.
         *
         * Looking one name up lists the whole directory, so a caller looping
         * over a library pays that once per trip: with 2000 trips backed up it
         * slowed to eight files a minute and got slower as the folder filled.
         * A caller that already enumerated the folder can hand the names
         * over, and the common case - a name the folder does not have - then
         * costs nothing. Keyed by the lowercased name, valued by the folder's
         * own spelling: shared storage ignores case, so a trip the app spells
         * "inmotion_v14" IS the folder's "Inmotion_V14", and treating it as
         * missing made the provider add "(1)", "(2)"... on every pass until it
         * refused at 32.
         */
        knownNames: Map<String, String>? = null,
    ): Boolean {
        val root = folder(settings) ?: return false
        if (!root.ensureDirectory(TRIPS_SUBFOLDER)) return false
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
                folderSpelling != null -> root.find(TRIPS_SUBFOLDER, folderSpelling)
                else -> root.list(TRIPS_SUBFOLDER)?.firstOrNull { it.name.equals(localFile.name, ignoreCase = true) }
            }
            if (existing != null && skipIfPresent) return true
            if (existing != null) root.delete(folderPath(TRIPS_SUBFOLDER, existing.name))
            root.write(folderPath(TRIPS_SUBFOLDER, localFile.name), localFile.readBytes())
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${localFile.name}", e)
            false
        }
    }

    // --- trips: the archive -------------------------------------------------

    /**
     * Move [fileName] into trips/archive inside the backup folder.
     *
     * Trips live in the folder's trips/ subdirectory, the same place
     * listFolderTripNames and downloadCsv read them from, so the archive sits
     * beside them rather than at the root next to themes/ and overlays/.
     *
     * @return true when the file was moved, or when there was nothing to move
     */
    fun archiveInBackupFolder(settings: AppSettings, fileName: String): Boolean {
        val root = folder(settings) ?: return true
        val trips = root.list(TRIPS_SUBFOLDER) ?: return true
        return try {
            // Shared storage ignores case and the folder may spell the name
            // its own way ("Inmotion_V14" for the app's "inmotion_v14").
            // An exact match alone read such a copy as "nothing to move", it
            // stayed in the folder, and the next sync brought the deleted
            // trip back.
            val doc = (trips.firstOrNull { it.name == fileName }
                ?: trips.firstOrNull { it.name.equals(fileName, ignoreCase = true) })
                ?.takeIf { !it.isDirectory } ?: return true
            val archiveDir = folderPath(TRIPS_SUBFOLDER, ARCHIVE_SUBFOLDER)
            if (!root.ensureDirectory(archiveDir)) return false
            val taken = root.list(archiveDir)?.mapTo(HashSet()) { it.name } ?: return false
            // An archive never destroys: a name already in there is an earlier
            // ride the rider archived, and a second one of the same name - the
            // same library restored and cleared again - has to sit beside it,
            // not on top of it. Dropbox does this for us with autorename; here
            // it has to be worked out, so the two sides behave the same way.
            val free = freeArchiveName(taken, fileName)
            val source = folderPath(TRIPS_SUBFOLDER, doc.name)
            // A storage-side move is a rename: no bytes read, no bytes
            // written. Only usable when the name is untaken, since the move
            // cannot rename as it goes. Every storage is allowed to refuse it,
            // so the copy below stays as the fallback.
            if (free == fileName) {
                val moved = runCatching { root.moveInto(source, archiveDir) }.getOrDefault(false)
                if (moved) return true
            }
            val bytes = root.read(source) ?: return false
            val wrote = runCatching { root.write(folderPath(archiveDir, free), bytes) }.getOrDefault(false)
            if (wrote) root.delete(source) else false
        } catch (e: Exception) {
            Log.w(TAG, "archive in backup folder failed for $fileName: ${e.message}")
            false
        }
    }

    // --- the JSON folders (themes, overlays) with Dropbox ------------------

    /**
     * The folder [root]'s [sub] as the shared [syncJsonFolder] sees it. Only
     * .json files take part: a theme or overlay is JSON, and anything else
     * would be renamed by the provider on the way in and come back every pass.
     */
    fun jsonFolder(root: BackupFolder, sub: String, create: Boolean): JsonFolder = object : JsonFolder {
        private var ready = false

        override suspend fun jsonFiles(): Map<String, ByteArray>? = withContext(Dispatchers.IO) {
            val entries = root.list(sub)
                ?: (if (create && root.ensureDirectory(sub)) root.list(sub) else null)
                ?: return@withContext null
            ready = true
            // A file the folder will not open is left out rather than sent
            // up empty.
            entries
                .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
                .mapNotNull { e -> root.read(folderPath(sub, e.name))?.let { e.name to it } }
                .toMap()
        }

        override suspend fun write(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
            if (!ready) return@withContext false
            // Replace an existing file in place. Deleting it and creating it
            // again races the provider, which then names the new one
            // "name (1).json" beside a missing original. A half-created empty
            // file would look like a conflict next pass and could overwrite
            // the real Dropbox copy on an APP choice, so write removes it
            // again and a failed download simply retries next time.
            try {
                root.write(folderPath(sub, name), bytes)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Sync [root]'s themes/ and overlays/ both ways with Dropbox
     * ([syncJsonFolder]). Downloads write into the folder's subfolders, where
     * the theme and preset stores read them.
     * @return number of files transferred (not counted as trips).
     */
    suspend fun syncJsonSubdirs(root: BackupFolder, choice: SyncChoice): Int {
        var count = 0
        for (sub in JSON_SUBFOLDERS) {
            try {
                count += syncJsonFolder(dropboxRepository, sub, jsonFolder(root, sub, create = true), choice) ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "sync /$sub failed: ${e.message}")
            }
        }
        return count
    }

    /** List both subfolders and count conflicts only (no transfers), so the
     *  ONE prompt can include themes/overlays conflicts before it shows. */
    suspend fun countJsonSubdirConflicts(root: BackupFolder): Int {
        var n = 0
        for (sub in JSON_SUBFOLDERS) {
            n += try {
                jsonFolder(root, sub, create = false).jsonFiles()
                    ?.let { planJsonFolder(dropboxRepository, sub, it) }?.conflicts?.size ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "count /$sub failed: ${e.message}")
                0
            }
        }
        return n
    }

    /**
     * The background pass's mirror of [root]'s themes/ and overlays/, so the
     * cloud copy is the WHOLE folder, not just trips and settings. The
     * background only adds what Dropbox lacks: overwriting by modified time
     * silently replaced another phone's edit, so a file both sides hold is
     * left to Sync all and its prompt.
     */
    suspend fun mirrorJsonSubdirsMissingOnDropbox(root: BackupFolder): FolderMirror {
        var uploaded = 0
        var anyFailed = false
        for (sub in JSON_SUBFOLDERS) {
            try {
                val subDir = withContext(Dispatchers.IO) { root.list(sub) } ?: continue
                // A failed listing is not an empty folder: skip, do not upload everything.
                val remoteSub = dropboxRepository.listFolder("/$sub") ?: continue
                val remoteLower = remoteSub.keys.mapTo(HashSet()) { it.lowercase() }
                for (doc in subDir) {
                    if (doc.isDirectory) continue
                    val name = doc.name
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    if (name.lowercase() in remoteLower) continue
                    val bytes = try {
                        withContext(Dispatchers.IO) { root.read(folderPath(sub, name)) }
                    } catch (e: Exception) { null }
                    if (bytes == null) { anyFailed = true; continue }
                    if (dropboxRepository.uploadFile("/$sub/$name", bytes)) {
                        uploaded++
                        Log.i(DROPBOX_WORKER_TAG, "Uploaded /$sub/$name")
                    } else anyFailed = true
                }
            } catch (e: Exception) {
                Log.w(DROPBOX_WORKER_TAG, "mirror /$sub failed: ${e.message}")
            }
        }
        return FolderMirror(uploaded, anyFailed)
    }

    /** [syncJsonSubdirs] over the backup folder; nothing without one. */
    suspend fun syncBackupSubdirs(settings: AppSettings, choice: SyncChoice): Int =
        folder(settings)?.let { syncJsonSubdirs(it, choice) } ?: 0

    /** [countJsonSubdirConflicts] over the backup folder; nothing without one. */
    suspend fun countBackupSubdirConflicts(settings: AppSettings): Int =
        folder(settings)?.let { countJsonSubdirConflicts(it) } ?: 0

    /** [mirrorJsonSubdirsMissingOnDropbox] over the backup folder; nothing without one. */
    suspend fun mirrorBackupSubdirsMissingOnDropbox(settings: AppSettings): FolderMirror =
        folder(settings)?.let { mirrorJsonSubdirsMissingOnDropbox(it) } ?: FolderMirror(0, false)
}
