package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.MoveOutcome
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the Dropbox trip sync needs from the phone it runs on, beyond the trip
 * files: Android's backup folder and WorkManager, the iPhone's themes folder
 * and in-app retry.
 */
interface DropboxTripHost {
    /**
     * Queue a background pass [attempt] steps up the retry curve
     * ([DropboxTripSync.delayForAttempt]), or after [delaySeconds] when given,
     * replacing one already queued.
     */
    fun scheduleRetry(attempt: Int, delaySeconds: Long? = null)

    /** Drop the queued background pass, if any. */
    fun cancelRetry()

    /**
     * Move [fileName] into the backup folder's trips/archive. True when it
     * moved, or when there is no folder or nothing in it to move.
     */
    suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String): Boolean

    /** Conflicts in the JSON folders (themes, overlays), counted into the one prompt. */
    suspend fun countFolderConflicts(settings: AppSettings): Int

    /** Sync the JSON folders both ways under the rider's [choice]; the files moved. */
    suspend fun syncFolders(settings: AppSettings, choice: SyncChoice): Int

    /** The background pass's folder mirror: only what Dropbox lacks goes up. */
    suspend fun mirrorFoldersInBackground(settings: AppSettings): FolderMirror

    /** A best-effort undo point taken before Dropbox's settings are applied. */
    suspend fun snapshotBeforeRestore()

    /** Queue the backup-folder mirror (Android's TripUploadWorker). */
    fun enqueueFolderUpload(settings: AppSettings)
}

/** What a background folder mirror did. */
data class FolderMirror(val uploaded: Int, val failed: Boolean)

/** What the foreground sync shows: its question, its progress bar, its result. */
interface DropboxSyncUi {
    /** Ask the rider about [count] conflicts and wait for the answer. */
    suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice
    fun progress(done: Int, total: Int)
    fun result(result: SyncResult)
}

/** How a background pass ended, for the platform's scheduler. */
enum class BackgroundOutcome {
    /** Finished, or rescheduled itself. */
    DONE,

    /** Could not even list Dropbox: run again on the platform's own retry. */
    RETRY,
}

/**
 * Trips both ways with Dropbox's /trips, the same on both phones: Android's
 * SyncManager and DropboxSyncWorker, and the iPhone's Sync now, run these
 * passes, so the folder layout, the file names and every rule about which
 * copy wins are one piece of code.
 *
 * - [syncAll]: the rider's Sync all. Lists both sides, asks once about real
 *   conflicts (trips, settings.json, the JSON folders), then moves files.
 * - [backgroundPass]: the quiet mirror. Uploads only what Dropbox lacks or
 *   what only this phone changed, then finishes a pull the rider asked for.
 * - [archiveTripFile] / [archiveTripFiles]: a delete that keeps the ride,
 *   moved into /trips/archive on every destination.
 */
class DropboxTripSync(
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val dropboxRepository: DropboxRepository,
    private val dropboxSettings: DropboxSettingsSync,
    private val files: TripFileStore,
    private val host: DropboxTripHost,
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val WORKER_TAG = "DropboxSyncWorker"

        /**
         * How long to leave Dropbox alone after it refuses us outright.
         *
         * Long on purpose: a refusal is not a hiccup, and retrying into one
         * every minute is what earns a longer one.
         */
        const val REFUSED_RETRY_SECONDS = 30L * 60

        /** Attempt number for the watchdog queued when a foreground sync
         *  starts: far enough up the backoff curve to sit ~5 minutes behind
         *  it, so it only ever runs if the foreground pass stopped. */
        const val WATCHDOG_ATTEMPT = 8

        /** Leaves room inside WorkManager's ~10 minute window for the upload
         *  half and the folder mirror that run before it. */
        const val DOWNLOAD_BUDGET_MS = 6 * 60_000L

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
        private const val BACKOFF_BASE_SECONDS = 15L
        private const val BACKOFF_MAX_SECONDS = 300L

        fun delayForAttempt(attempt: Int): Long {
            if (attempt <= 0) return 0L
            val shift = (attempt - 1).coerceAtMost(20)
            val raw = BACKOFF_BASE_SECONDS * (1L shl shift)
            return raw.coerceAtMost(BACKOFF_MAX_SECONDS)
        }
    }

    /** Held by every Dropbox pass, foreground or background, so two never move the same files. */
    val passLock = Mutex()

    /** Remember [bytes] as the version of [fileName] both sides now hold. */
    suspend fun markDropboxSynced(fileName: String, bytes: ByteArray) {
        tripDao.setDropboxSyncedHash(fileName, DropboxContentHash.of(bytes))
    }

    /**
     * Upload one trip the rider just edited or made (a rename, a wheel change,
     * a split piece, a join) right away, rather than at the next pass. The
     * row shows backing up, then backed up or failed; a failure queues the
     * first retry. Android's pushEditedTripNow and the iPhone's resync both
     * run it.
     */
    suspend fun pushEditedTrip(fileName: String) {
        val file = files.file(fileName)
        if (!file.exists()) return
        tripDao.setDropboxStatusByName(fileName, 1, null)
        val editedBytes = file.readBytes()
        val sec = dropboxRepository.uploadFileStamped("/trips/$fileName", editedBytes)
        if (sec != null) {
            markDropboxSynced(fileName, editedBytes)
            if (sec > 0L) file.setLastModified(sec * 1000L)
            tripDao.setDropboxStatusByName(fileName, 2, System.currentTimeMillis())
        } else {
            tripDao.setDropboxStatusByName(fileName, 3, null)
            host.scheduleRetry(1)
        }
    }

    /**
     * Follow archives made on another phone. A split, an extend or a delete
     * with "archive" moves the trip on Dropbox into /trips/archive. A phone
     * that still had it then saw a trip Dropbox lacked and uploaded it again,
     * and the ride came back everywhere beside the pieces that replaced it.
     *
     * Of [localOnly] (trip files this phone has and Dropbox's /trips does not),
     * those whose identical copy is in Dropbox's archive are archived here
     * too: the backup-folder copy moves to its archive, then the phone's row
     * and file go. A copy that differs is left alone, so nothing that exists
     * once is dropped. Returns the lowercased names handled.
     */
    suspend fun followDropboxArchive(settings: AppSettings, localOnly: Collection<TripFile>): Set<String> {
        if (localOnly.isEmpty()) return emptySet()
        val archive = dropboxRepository.listFolder("/trips/archive") ?: return emptySet()
        val archivedHash = archive.entries.associate { it.key.lowercase() to it.value.contentHash }
        val handled = HashSet<String>()
        for (file in localOnly) {
            val key = file.name.lowercase()
            val remoteHash = archivedHash[key] ?: continue
            if (remoteHash != DropboxContentHash.of(file.readBytes())) continue
            if (settings.syncFolderUri != null && !host.archiveInBackupFolder(settings, file.name)) continue
            tripDao.findByFileName(file.name)?.let { tripDao.delete(it) }
            file.delete()
            handled += key
            Log.i(TAG, "${file.name} was archived on another phone; archived here too")
        }
        return handled
    }

    /**
     * The rider's Sync all: compare the trips folder against /trips/, prompt
     * the rider on real conflicts with the same dialog the folder sync uses,
     * then upload / download to reconcile. settings.json and the JSON folders
     * ride along under the same answer.
     *
     * Marks the trips pending first, so the persistent indicator shows until
     * this pass (or the background one) clears it, and holds [passLock].
     */
    suspend fun syncAll(ui: DropboxSyncUi) {
        // A manual sync is starting: mark trips as pending so the
        // persistent indicator shows until this pass (or the background
        // worker) clears it. runDropboxSync flips it back on a clean pass.
        settingsRepository.update { it.copy(dropboxSyncPending = true) }
        passLock.withLock { runDropboxSync(ui) }
    }

    private suspend fun runDropboxSync(ui: DropboxSyncUi) {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) {
            ui.result(SyncResult.NoFolder)
            return
        }
        dropboxRepository.clearRateLimited()
        val remote = dropboxRepository.listFolder("/trips")
        if (remote == null) {
            // Auth or network failed: same UX as "no folder" since the rider
            // can't act on the sync until they re-link or reconnect.
            ui.result(SyncResult.NoFolder)
            return
        }
        val localFiles = files.csvFiles()
        val localByLower = localFiles.associateBy { it.name.lowercase() }
        val remoteByLower = remote.keys.associateBy { it.lowercase() }
        val remoteMetaByLower = remote.entries.associate { it.key.lowercase() to it.value }

        // A same-name file is a conflict only when the copies differ. Same name
        // and same size is already synced: neither a conflict nor work.
        val bothNames = remoteByLower.keys intersect localByLower.keys
        // Same name, different size: the copies differ. The fingerprint of the
        // version last synced tells which side moved on, so an edit made on one
        // phone (a rename, a wheel change) transfers without asking the other.
        val syncedHash = tripDao.observeAll().first()
            .associate { it.fileName.lowercase() to it.dropboxSyncedHash }
        val phoneMovedOn = HashSet<String>()
        val dropboxMovedOn = HashSet<String>()
        val conflictKeys = HashSet<String>()
        for (key in bothNames) {
            val local = localByLower.getValue(key)
            val remoteMeta = remoteMetaByLower[key]
            if (local.length() == (remoteMeta?.size ?: -2L)) {
                // Already matching. Remember it, so a later edit on one side is
                // recognised as one-sided instead of asked about.
                if (syncedHash[key] == null && remoteMeta?.contentHash != null &&
                    syncedHash.containsKey(key)) tripDao.setDropboxSyncedHash(local.name, remoteMeta.contentHash)
                continue
            }
            val base = syncedHash[key]
            val remoteHash = remoteMeta?.contentHash
            val localHash = if (base != null) DropboxContentHash.of(local.readBytes()) else null
            when {
                base != null && remoteHash == base && localHash != base -> phoneMovedOn += key
                base != null && localHash == base && remoteHash != base -> dropboxMovedOn += key
                else -> conflictKeys += key
            }
        }
        val remoteOnly = remoteByLower.keys - localByLower.keys
        val archivedElsewhere = followDropboxArchive(
            settings, (localByLower.keys - remoteByLower.keys).mapNotNull { localByLower[it] })
        val localOnly = localByLower.keys - remoteByLower.keys - archivedElsewhere

        // --- Settings (whole-blob, 3-way vs the stored baseline) ---
        val settingsPlan = dropboxSettings.plan(settings)
        var settingsAction = settingsPlan.action
        val settingsConflict = settingsPlan.conflict

        val subdirConflicts = host.countFolderConflicts(settings)
        val totalConflicts = conflictKeys.size + (if (settingsConflict) 1 else 0) + subdirConflicts
        var choice = SyncChoice.IGNORE
        if (totalConflicts > 0) {
            choice = ui.askConflict(
                totalConflicts,
                firstSettings = settingsConflict && settings.dropboxSettingsBaseHash.isEmpty(),
            )
            if (choice == SyncChoice.CANCEL) {
                // Rider aborted at the conflict prompt: clear the pending flag +
                // count so the persistent indicator disappears, and drop the
                // watchdog run, or it would carry on five minutes later with
                // the very transfers the rider just declined.
                host.cancelRetry()
                settingsRepository.update {
                    it.copy(dropboxSyncPending = false, dropboxPendingCount = 0,
                        dropboxSyncTotal = 0, dropboxPullRequested = false)
                }
                return
            }
        }

        val toUpload = mutableListOf<TripFile>()
        val toDownload = mutableListOf<String>()
        localOnly.forEach { key -> localByLower[key]?.let { toUpload += it } }
        remoteOnly.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
        phoneMovedOn.forEach { key -> localByLower[key]?.let { toUpload += it } }
        dropboxMovedOn.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
        when (choice) {
            SyncChoice.APP -> conflictKeys.forEach { key -> localByLower[key]?.let { toUpload += it } }
            SyncChoice.FOLDER -> conflictKeys.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        // The shared choice resolves the settings conflict too.
        if (settingsConflict) settingsAction = when (choice) {
            SyncChoice.APP -> SettingsSyncAction.UPLOAD
            SyncChoice.FOLDER -> SettingsSyncAction.APPLY
            else -> SettingsSyncAction.NONE // IGNORE / skip
        }

        // Settings go first, right after the decision: deciding now and writing
        // after a library-sized transfer left minutes for another phone to
        // change them in between, and that change would have been overwritten.
        val settingsOutcome = dropboxSettings.carryOut(settingsPlan, settingsAction) {
            host.snapshotBeforeRestore() // best-effort undo point (needs a SAF folder)
        }
        val settingsFailed = settingsOutcome.failed

        val total = toUpload.size + toDownload.size

        var done = 0
        // Count files that could NOT be transferred. The most common cause is the
        // OS cutting the app's network the moment it leaves the foreground: the
        // Dropbox host stops resolving and every upload throws. Previously the
        // result was ignored, so a half-skipped sync still reported "Finished".
        var failed = if (settingsFailed) 1 else 0
        // Only show the determinate "X of Y" bar when there are trips to move.
        // When total is 0 (everything already backed up) it stays on the
        // indeterminate "Checking" state while settings/themes mirror, instead of
        // a meaningless "0 of 0".
        if (total > 0) ui.progress(done, total)
        // Seed the pending-count indicator with the trips to upload, then
        // decrement live as each one lands so the count reflects trips remaining.
        settingsRepository.update {
            it.copy(dropboxPendingCount = toUpload.size, dropboxSyncTotal = toUpload.size)
        }

        for (file in toUpload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            tripDao.setDropboxStatusByName(file.name, 1, null)
            val uploadBytes = file.readBytes()
            val storedAtSec = dropboxRepository.uploadFileStamped("/trips/${file.name}", uploadBytes)
            if (storedAtSec != null) {
                markDropboxSynced(file.name, uploadBytes)
                if (storedAtSec > 0L) file.setLastModified(storedAtSec * 1000L)
                tripDao.setDropboxStatusByName(file.name, 2, System.currentTimeMillis())
                settingsRepository.update {
                    it.copy(dropboxPendingCount = (it.dropboxPendingCount - 1).coerceAtLeast(0))
                }
            } else {
                tripDao.setDropboxStatusByName(file.name, 3, null)
                failed++
            }
            done++
            ui.progress(done, total)
        }

        for (name in toDownload) {
            currentCoroutineContext().ensureActive() // stop cleanly if cancelled
            val bytes = dropboxRepository.downloadFile("/trips/$name")
            if (bytes == null) failed++
            if (bytes != null) {
                val dest = files.writeWhole(name, bytes)
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
                val meta = TripCsvMeta.read(dest)
                if (existing == null) {
                    tripDao.insert(TripRecord(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        fileName = name,
                        distanceKm = meta.distanceKm,
                        // The file carries the rider's name for it; a downloaded
                        // trip used to arrive nameless and show its date instead.
                        customName = meta.name,
                        wheelMetaJson = meta.wheelJson,
                        // It came from Dropbox, so that is where it already is.
                        dropboxStatus = 2,
                        dropboxUploadedAt = System.currentTimeMillis(),
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
                        wheelMetaJson = meta.wheelJson ?: existing.wheelMetaJson,
                        // The backup folder now holds the copy the rider chose
                        // against, so send this one over it.
                        uploadStatus = if (settings.syncFolderUri != null) 1 else existing.uploadStatus,
                    ))
                }
                markDropboxSynced(name, bytes)
            }
            done++
            ui.progress(done, total)
        }

        host.syncFolders(settings, choice)

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
            // themes / overlays mirror - counting those made an all-synced
            // pass that merely refreshed settings report "1 trip". When no trip
            // moved, say "already up to date" instead of "0 trips".
            ui.result(if (total == 0) SyncResult.UpToDate else SyncResult.Finished(total))
            if (settings.syncFolderUri != null) host.enqueueFolderUpload(settings)
            settingsRepository.update { it.copy(dropboxPullRequested = false) }
        } else if (dropboxRepository.refused) {
            // Dropbox has cut us off rather than asked us to wait. Coming back
            // in a minute, as an ordinary failure would, is what keeps us cut
            // off - so wait properly. The rider is told the same thing either
            // way: Dropbox is limiting us and their trips are not lost.
            ui.result(SyncResult.RateLimited(total - failed, total))
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            host.scheduleRetry(0, delaySeconds = REFUSED_RETRY_SECONDS)
        } else if (dropboxRepository.rateLimited) {
            // Say which it was. The retry worker still picks the rest up, but
            // the rider is owed the reason their library stopped halfway.
            ui.result(SyncResult.RateLimited(total - failed, total))
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            host.scheduleRetry(0)
        } else {
            // Hand the skipped trips to the retry worker (missing/newer only), so
            // they upload once the network is back instead of being silently lost.
            // No toast: the persistent pending flag + count + indicator cover it,
            // and the worker keeps retrying until every trip lands or the rider
            // cancels.
            settingsRepository.update {
                it.copy(dropboxSyncPending = true, dropboxPendingCount = failed)
            }
            host.scheduleRetry(0)
        }
    }

    /**
     * Mirror local trips + settings into Dropbox, quietly.
     *
     * Comparison-based rather than per-file-status: list both sides, upload
     * anything that's newer locally or missing remote, then pull down what
     * Dropbox has and this phone does not, when the rider asked for that.
     *
     * The download half matters for a rider setting a new phone up from a big
     * library. It used to exist only in the foreground sync, which takes the
     * better part of an hour for a couple of thousand trips - so it finished
     * only if they sat and watched. Here it is bounded by [downloadBudgetMs],
     * and whatever is left schedules another run, so a library arrives across
     * several passes without anyone watching.
     *
     * The caller holds [passLock] and does not start this while Sync all runs.
     *
     * @param attempt where this pass sits on the retry curve
     * @param isStopped the rider cancelled, or the platform is taking the job away
     * @param foregroundRunning Sync all has started and is waiting on this pass
     */
    suspend fun backgroundPass(
        attempt: Int,
        isStopped: () -> Boolean,
        foregroundRunning: () -> Boolean,
        downloadBudgetMs: Long = DOWNLOAD_BUDGET_MS,
    ): BackgroundOutcome {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) {
            Log.i(WORKER_TAG, "Not linked, skipping")
            // Nothing can sync without a link, so drop the pending flag + count
            // (and the indicator) rather than leaving them stuck on forever.
            settingsRepository.update {
                it.copy(dropboxSyncPending = false, dropboxPendingCount = 0, dropboxSyncTotal = 0)
            }
            return BackgroundOutcome.DONE
        }

        // --- Trips: upload anything local that's missing or newer on Dropbox.
        val remoteTrips = dropboxRepository.listFolder("/trips")
        if (remoteTrips == null) {
            // A refusal fails here, at the first call of the pass, so this is
            // the return that ran once a minute all night. WorkManager's retry
            // would do the same again; hand it a long delay instead.
            if (dropboxRepository.refused) {
                host.scheduleRetry(0, delaySeconds = REFUSED_RETRY_SECONDS)
                Log.w(WORKER_TAG, "Dropbox refused us; backing off " +
                    "${REFUSED_RETRY_SECONDS / 60} min")
                return BackgroundOutcome.DONE
            }
            Log.w(WORKER_TAG, "list_folder failed, will retry")
            return BackgroundOutcome.RETRY
        }
        // Only trip CSVs belong in the Dropbox /trips folder. The trips dir also
        // holds transient bundles like "trips_export.dbb" (the Export-all-as-ZIP
        // share file); without this filter the sync uploaded those too, so a
        // "trips_export.zip/.dbb" leaked into the rider's Dropbox alongside the
        // real trips.
        val remoteLower = remoteTrips.keys.mapTo(HashSet()) { it.lowercase() }
        val allLocal = files.csvFiles()
        val archivedElsewhere = followDropboxArchive(
            settings, allLocal.filter { it.name.lowercase() !in remoteLower })
        val localFiles = allLocal.filter { it.name.lowercase() !in archivedElsewhere }
        // Current per-trip Dropbox state, to mark verified backups without
        // rewriting rows that already say so on every pass.
        val knownStatus = tripDao.observeAll().first()
            .associate { it.fileName.lowercase() to it.dropboxStatus }
        var anyFailed = false
        var uploaded = 0
        // How many trips still need uploading, decided by needsUpload below
        // (UploadPolicy: Dropbox's timestamp, then the content hash). failedTrips
        // counts the needed uploads that did not make it this pass.
        // Dropbox ignores case like the phone does; a name the two spell
        // differently is still the same trip, not one to upload again.
        val remoteByLower = remoteTrips.mapKeys { it.key.lowercase() }
        val syncedHash = tripDao.observeAll().first()
            .associate { it.fileName.lowercase() to it.dropboxSyncedHash }
        fun needsUpload(f: TripFile): Boolean {
            val remote = remoteByLower[f.name.lowercase()]
            // The copies differ and this phone knows the version last synced: only
            // an untouched Dropbox copy may be replaced. If Dropbox moved on, it is
            // another phone's edit, alone or alongside one made here, and the
            // background has no business choosing; Sync all brings it down or asks.
            val base = syncedHash[f.name.lowercase()]
            if (remote != null && base != null && remote.size != f.length()) {
                if (remote.contentHash == base) return true
                Log.i(WORKER_TAG, "${f.name} changed on Dropbox since this phone synced it; not overwriting")
                return false
            }
            val up = UploadPolicy.needsUpload(
                remoteSize = remote?.size,
                remoteModifiedSec = remote?.serverModifiedSec ?: 0L,
                localSize = f.length(),
                localModifiedMs = f.lastModified(),
                remoteHash = remote?.contentHash,
                localHash = { runCatching { DropboxContentHash.of(f.readBytes()) }.getOrNull() },
            )
            if (!up && remote != null && remote.size != f.length()) {
                Log.i(WORKER_TAG, "${f.name} changed on Dropbox since this phone wrote it; not overwriting")
            }
            return up
        }
        val needUpload = localFiles.count { needsUpload(it) }
        settingsRepository.update {
            it.copy(
                dropboxPendingCount = needUpload,
                dropboxSyncTotal = needUpload,
                dropboxSyncPending = needUpload > 0,
            )
        }
        var failedTrips = 0
        for (file in localFiles) {
            // Rider tapped Cancel (cancelUniqueWork flips isStopped): stop promptly
            // and leave the flag/count alone - stopDropboxSync already cleared them.
            if (isStopped()) return BackgroundOutcome.DONE
            // Sync all started: it waits on this pass, so hand over now.
            if (foregroundRunning()) return BackgroundOutcome.DONE
            val name = file.name
            if (!needsUpload(file)) {
                // Not uploading because Dropbox already holds this trip - an
                // identical copy, or a newer one edited elsewhere. That is a
                // verified backup, so record it, with Dropbox's own date. The
                // rows this fills are the old library: trips that were synced
                // long before per-trip Dropbox state existed showed "not
                // backed up yet" although the check right here had proved
                // otherwise on every pass, and the answer was thrown away.
                val known = knownStatus[name.lowercase()]
                val remote = remoteByLower[name.lowercase()]
                if (remote != null && known != null && known != 2) {
                    tripDao.setDropboxStatusByName(
                        name, 2, remote.serverModifiedSec * 1000L)
                }
                continue
            }
            tripDao.setDropboxStatusByName(name, 1, null)
            val uploadBytes = file.readBytes()
            val storedAtSec = dropboxRepository.uploadFileStamped("/trips/$name", uploadBytes)
            if (storedAtSec != null) {
                markDropboxSynced(name, uploadBytes)
                tripDao.setDropboxStatusByName(name, 2, System.currentTimeMillis())
                // Wear Dropbox's timestamp, so an untouched file matches the
                // copy it was sent as. Anything that rewrites the file after
                // this - a rename, a wheel change - moves it off that mark, and
                // that is what the next pass looks for, rather than a change in
                // the file's length that an edit need not produce.
                if (storedAtSec > 0L) file.setLastModified(storedAtSec * 1000L)
                uploaded++
                // Decrement live so the indicator reflects trips remaining.
                settingsRepository.update {
                    it.copy(dropboxPendingCount = (it.dropboxPendingCount - 1).coerceAtLeast(0))
                }
                Log.i(WORKER_TAG, "Uploaded $name")
            } else {
                tripDao.setDropboxStatusByName(name, 3, null)
                anyFailed = true
                failedTrips++
                Log.w(WORKER_TAG, "Upload failed for $name")
            }
        }

        // --- Settings.json: the same 3-way rule as the foreground sync, so a
        //     background pass never overwrites another phone's edit.
        val now = System.currentTimeMillis()
        if (!dropboxSettings.pushIfOnlyPhoneChanged()) anyFailed = true

        // --- Themes + overlays: mirror the rest of the backup folder so the
        //     cloud copy is the WHOLE folder, not just trips + settings. The
        //     background only adds what Dropbox lacks: overwriting by modified
        //     time silently replaced another phone's edit, so a file both sides
        //     hold is left to Sync all and its prompt.
        val mirror = host.mirrorFoldersInBackground(settings)
        uploaded += mirror.uploaded
        if (mirror.failed) anyFailed = true

        // The "Syncing trips…" indicator reflects TRIPS only. A settings.json /
        // themes / overlays upload failure still schedules a retry, but must NOT
        // keep the trips indicator up: that left an endless indeterminate
        // "Syncing trips…" (total 0) showing on every open with no trip pending.
        val tripsPending = failedTrips > 0
        settingsRepository.update {
            it.copy(
                dropboxSyncPending = tripsPending,
                dropboxPendingCount = failedTrips,
                dropboxSyncTotal = if (tripsPending) it.dropboxSyncTotal else 0,
                // Only a fully clean pass (trips + settings + folder) stamps the time.
                dropboxLastSyncAt = if (!anyFailed) now else it.dropboxLastSyncAt,
            )
        }

        // --- Trips Dropbox has that this phone does not. Bounded, because a
        //     job gets about ten minutes; the rest comes on the next run.
        // Never alongside a foreground pass: both would pull the same files.
        val stillMissing = if (foregroundRunning()) {
            Log.i(WORKER_TAG, "Foreground sync is running, leaving the download to it")
            0
        } else downloadMissingTrips(
            budgetMs = downloadBudgetMs,
            isStopped = { isStopped() || foregroundRunning() },
        )
        if (stillMissing > 0) Log.i(WORKER_TAG, "$stillMissing trips still to come down")

        // Dropbox refusing us is not a transient failure to retry through. Its
        // edge cuts a client off wholesale, and the ordinary backoff starts at
        // a minute, so the app spends the outage knocking once a minute and
        // earning more of it. Leave it alone for half an hour instead.
        if (dropboxRepository.refused) {
            host.scheduleRetry(0, delaySeconds = REFUSED_RETRY_SECONDS)
            Log.w(WORKER_TAG, "Dropbox refused us; backing off " +
                "${REFUSED_RETRY_SECONDS / 60} min")
            return BackgroundOutcome.DONE
        }
        if (anyFailed || stillMissing > 0) {
            host.scheduleRetry(attempt + 1)
            Log.i(WORKER_TAG, "Retry scheduled (uploaded $uploaded, $stillMissing left to download)")
        } else {
            Log.i(WORKER_TAG, "Sync OK (uploaded $uploaded)")
        }
        return BackgroundOutcome.DONE
    }

    /**
     * Pull the trips Dropbox has and this phone does not, for up to [budgetMs].
     *
     * The background pass owns the upload half; this is the download half it
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
        val localLower = files.files().map { it.name.lowercase() }.toHashSet()
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
            val dest = files.writeWhole(name, bytes)
            // Same reason as the foreground pass: a file wearing this moment's
            // timestamp reads as edited here, and a whole pulled library would
            // be uploaded straight back.
            remote[name]?.let { dest.setLastModified(it.serverModifiedSec * 1000L) }
            if (tripDao.findByFileName(name) == null) {
                val meta = TripCsvMeta.read(dest)
                tripDao.insert(TripRecord(
                    startTime = meta.startTime,
                    endTime = meta.endTime,
                    fileName = name,
                    distanceKm = meta.distanceKm,
                    // The file carries the rider's name for it; a downloaded
                    // trip used to arrive nameless and show its date instead.
                    customName = meta.name,
                    wheelMetaJson = meta.wheelJson,
                    // Pending when a backup folder exists, so the folder worker
                    // mirrors it: same rule as the foreground pass.
                    uploadStatus = if (settings.syncFolderUri != null) 4 else 0,
                    // It came from Dropbox, so that is where it already is.
                    dropboxStatus = 2,
                    dropboxUploadedAt = System.currentTimeMillis(),
                ))
            }
            markDropboxSynced(name, bytes)
            left--
            settingsRepository.update { it.copy(dropboxPendingCount = left) }
        }
        if (left < missing.size && settings.syncFolderUri != null) host.enqueueFolderUpload(settings)
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
     * phone's copy goes last of all, and is the caller's: while it is here the
     * rider can try again, and deleting it while a backup still holds the file
     * is what produces a trip that keeps coming back.
     *
     * @return true only when the file is archived everywhere it existed
     */
    suspend fun archiveTripFile(fileName: String): Boolean {
        val settings = settingsRepository.get()
        val moved = if (settings.dropboxAccessToken.isNotBlank()) {
            dropboxRepository.moveFile("/trips/$fileName", "/trips/archive/$fileName")
        } else null
        val folderOk = moved !is MoveOutcome.Failed && host.archiveInBackupFolder(settings, fileName)
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
        val accepted = fileNames.filterTo(HashSet()) { host.archiveInBackupFolder(settings, it) }
        val (done, putBack) = ArchivePolicy.decideBatch(fileNames, dropboxOk = true, folderAccepted = accepted)
        if (dropboxLinked && putBack.isNotEmpty()) {
            // Whatever the folder would not take goes back to /trips, so the
            // two sides never disagree about where a trip lives.
            dropboxRepository.moveFilesBatch(putBack.map { "/trips/archive/$it" to "/trips/$it" })
        }
        return done
    }
}
