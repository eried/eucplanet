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
import java.io.File

/**
 * Mirror local trips + settings into the linked Dropbox App Folder.
 *
 * Comparison-based rather than per-file-status: list both sides, upload
 * anything that's newer locally or missing remote. No download in this
 * pass (Phase 3 brings the conflict dialog + restore flow). Skipping the
 * Room migration for a status column keeps Phase 2 short; the trade-off
 * is an extra /files/list_folder round-trip per sync, which is cheap
 * compared to the upload bodies themselves.
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

    companion object { private const val TAG = "DropboxSyncWorker" }

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
        fun needsUpload(f: File): Boolean {
            val remote = remoteTrips[f.name]
            return remote == null || remote.size != f.length()
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

        if (anyFailed) {
            val attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0)
            syncManager.scheduleDropboxSyncAttempt(attempt + 1)
            Log.i(TAG, "Some uploads failed; retry scheduled (uploaded $uploaded)")
        } else {
            Log.i(TAG, "Sync OK (uploaded $uploaded)")
        }
        return Result.success()
    }
}
