package com.eried.eucplanet.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.store.SettingsJson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Mirror local trips + settings into the linked Dropbox App Folder.
 *
 * Comparison-based rather than per-file-status: list both sides, upload
 * anything that's newer locally or missing remote, then pull down what
 * Dropbox has and this phone does not.
 *
 * The download half matters for a rider setting a new phone up from a big
 * library. It used to exist only in the foreground sync, which takes the
 * better part of an hour for a couple of thousand trips - so it finished only
 * if they sat and watched, and nothing carried on when Android reclaimed the
 * app. Here it is bounded by the time WorkManager gives a job, and whatever is
 * left schedules another run, so a library arrives across several passes
 * without anyone watching.
 */
@HiltWorker
class DropboxSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val dropboxRepository: DropboxRepository,
    private val syncManager: SyncManager,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DropboxSyncWorker"

        /**
         * How far Dropbox's timestamp may run ahead of ours before we read it
         * as someone else's edit rather than our own upload.
         *
         * Dropbox stamps server_modified with its clock, we stamp uploadedAt
         * with the phone's, and the two are never exactly the same. Minutes of
         * slack cost nothing: an edit made in another tool is hours or days
         * later, not five minutes.
         */
        private const val EDIT_GRACE_MS = 5L * 60 * 1000
        /** Leaves room inside WorkManager's ~10 minute window for the upload
         *  half and the folder mirror that run before it. */
        private const val DOWNLOAD_BUDGET_MS = 6 * 60_000L
    }

    override suspend fun doWork(): Result {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) {
            Log.i(TAG, "Not linked, skipping")
            // Nothing can sync without a link, so drop the pending flag + count
            // (and the indicator) rather than leaving them stuck on forever.
            settingsRepository.update {
                it.copy(dropboxSyncPending = false, dropboxPendingCount = 0, dropboxSyncTotal = 0)
            }
            return Result.success()
        }

        // --- Trips: upload anything local that's missing or newer on Dropbox.
        val remoteTrips = dropboxRepository.listFolder("/trips")
        if (remoteTrips == null) {
            // A refusal fails here, at the first call of the pass, so this is
            // the return that ran once a minute all night. WorkManager's retry
            // would do the same again; hand it a long delay instead.
            if (dropboxRepository.refused) {
                syncManager.scheduleDropboxSyncAttempt(
                    0, delaySeconds = SyncManager.REFUSED_RETRY_SECONDS
                )
                Log.w(TAG, "Dropbox refused us; backing off " +
                    "${SyncManager.REFUSED_RETRY_SECONDS / 60} min")
                return Result.success()
            }
            Log.w(TAG, "list_folder failed, will retry")
            return Result.retry()
        }
        // Only trip CSVs belong in the Dropbox /trips folder. The trips dir also
        // holds transient bundles like "trips_export.dbb" (the Export-all-as-ZIP
        // share file); without this filter the sync uploaded those too, so a
        // "trips_export.zip/.dbb" leaked into the rider's Dropbox alongside the
        // real trips.
        val localFiles = tripRepository.getTripsDir()
            .listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.toList().orEmpty()
        var anyFailed = false
        var uploaded = 0
        // How many trips still need uploading. A trip is "already up" when Dropbox
        // holds a file of the SAME byte length (trip CSVs are append-only, so a
        // matching size means matching content). We deliberately do NOT compare by
        // modified-time: the local mtime gets bumped by on-device rewrites, which
        // made every trip look "newer" and re-upload forever, so the sync never
        // converged and the indicator never cleared. failedTrips tracks how many
        // of the needed uploads never made it this pass.
        // When we last put each trip on Dropbox, so an edit made there can be
        // told apart from one made here.
        val uploadedAt = tripRepository.allTrips.first()
            .associate { it.fileName.lowercase() to (it.uploadedAt ?: 0L) }
        fun needsUpload(f: File): Boolean {
            val remote = remoteTrips[f.name] ?: return true
            if (remote.size == f.length()) return false
            // Sizes differ, and "ours is different" used to be enough to
            // upload over theirs. It is not: renaming a trip in another tool
            // writes the name into the file, which changes its size, so the
            // next pass quietly replaced the renamed copy with this phone's
            // nameless one. Riders reported renames coming back "normal" days
            // later, and this is what did it.
            //
            // If Dropbox's copy has changed since we last put it there, it was
            // changed somewhere else. Leave it alone. The grace window covers
            // the clock skew between this phone and Dropbox's own timestamp on
            // our upload, which is seconds, not days.
            val ours = uploadedAt[f.name.lowercase()] ?: 0L
            if (ours > 0L && remote.serverModified > ours + EDIT_GRACE_MS) {
                Log.i(TAG, "${f.name} changed on Dropbox since we uploaded it; not overwriting")
                return false
            }
            return true
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
            if (isStopped) return Result.success()
            val name = file.name
            if (!needsUpload(file)) continue
            val ok = dropboxRepository.uploadFile("/trips/$name", file.readBytes())
            if (ok) {
                uploaded++
                // Decrement live so the indicator reflects trips remaining.
                settingsRepository.update {
                    it.copy(dropboxPendingCount = (it.dropboxPendingCount - 1).coerceAtLeast(0))
                }
                Log.i(TAG, "Uploaded $name")
            } else {
                anyFailed = true
                failedTrips++
                Log.w(TAG, "Upload failed for $name")
            }
        }

        // --- Settings.json: hash-compare so we don't burn requests on
        //     identical content. Dropbox API doesn't return our content
        //     hash without an extra GET so just compare the bytes that
        //     would be uploaded against a remote_modified gate using the
        //     last successful sync timestamp persisted in AppSettings.
        val settingsJson = SettingsJson.toJson(settings).toString().toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis()
        val rootList = dropboxRepository.listFolder("")
        val remoteSettingsMod = rootList?.get("settings.json")?.serverModified
        val lastSync = settings.dropboxLastSyncAt / 1000L
        if (remoteSettingsMod == null || remoteSettingsMod < lastSync) {
            val ok = dropboxRepository.uploadFile("/settings.json", settingsJson)
            if (!ok) anyFailed = true
        }

        // --- Themes + overlays: mirror the rest of the backup folder so the
        //     cloud copy is the WHOLE folder, not just trips + settings. These
        //     live as files in the SAF backup folder (ThemeStore /
        //     OverlayPresetStore); upload anything missing or newer remotely,
        //     same file-by-file conflict rule as trips.
        val folder = syncManager.getSyncFolder(settings)
        if (folder != null) {
            for (sub in listOf("themes", "overlays")) {
                try {
                    val subDir = folder.findFile(sub)?.takeIf { it.isDirectory } ?: continue
                    val remoteSub = dropboxRepository.listFolder("/$sub") ?: emptyMap()
                    for (doc in subDir.listFiles()) {
                        if (!doc.isFile) continue
                        val name = doc.name ?: continue
                        val localMod = doc.lastModified() / 1000L
                        if (remoteSub[name]?.let { it.serverModified >= localMod } == true) continue
                        val bytes = try {
                            applicationContext.contentResolver
                                .openInputStream(doc.uri)?.use { it.readBytes() }
                        } catch (e: Exception) { null }
                        if (bytes == null) { anyFailed = true; continue }
                        if (dropboxRepository.uploadFile("/$sub/$name", bytes)) {
                            uploaded++
                            Log.i(TAG, "Uploaded /$sub/$name")
                        } else anyFailed = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "mirror /$sub failed: ${e.message}")
                }
            }
        }

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
        val stillMissing = if (syncManager.syncRunning.value) {
            Log.i(TAG, "Foreground sync is running, leaving the download to it")
            0
        } else syncManager.downloadMissingTrips(
            budgetMs = DOWNLOAD_BUDGET_MS,
            isStopped = { isStopped },
        )
        if (stillMissing > 0) Log.i(TAG, "$stillMissing trips still to come down")

        // Dropbox refusing us is not a transient failure to retry through. Its
        // edge cuts a client off wholesale, and the ordinary backoff starts at
        // a minute, so the app spends the outage knocking once a minute and
        // earning more of it. Leave it alone for half an hour instead.
        if (dropboxRepository.refused) {
            syncManager.scheduleDropboxSyncAttempt(
                0, delaySeconds = SyncManager.REFUSED_RETRY_SECONDS
            )
            Log.w(TAG, "Dropbox refused us; backing off " +
                "${SyncManager.REFUSED_RETRY_SECONDS / 60} min")
            return Result.success()
        }
        if (anyFailed || stillMissing > 0) {
            val attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0)
            syncManager.scheduleDropboxSyncAttempt(attempt + 1)
            Log.i(TAG, "Retry scheduled (uploaded $uploaded, $stillMissing left to download)")
        } else {
            Log.i(TAG, "Sync OK (uploaded $uploaded)")
        }
        return Result.success()
    }
}
