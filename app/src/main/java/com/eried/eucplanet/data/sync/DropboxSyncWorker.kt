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
            return Result.success()
        }

        // --- Trips: upload anything local that's missing or newer on Dropbox.
        val remoteTrips = dropboxRepository.listFolder("/trips")
        if (remoteTrips == null) {
            Log.w(TAG, "list_folder failed, will retry")
            return Result.retry()
        }
        val localFiles = tripRepository.getTripsDir().listFiles { f -> f.isFile }
            ?.toList().orEmpty()
        var anyFailed = false
        var uploaded = 0
        for (file in localFiles) {
            val name = file.name
            val remoteMod = remoteTrips[name]
            val localMod = file.lastModified() / 1000L
            if (remoteMod != null && remoteMod >= localMod) continue
            val ok = dropboxRepository.uploadFile("/trips/$name", file.readBytes())
            if (ok) {
                uploaded++
                Log.i(TAG, "Uploaded $name")
            } else {
                anyFailed = true
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
        val remoteSettingsMod = rootList?.get("settings.json")
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
                        if (remoteSub[name]?.let { it >= localMod } == true) continue
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

        if (!anyFailed) {
            settingsRepository.update { it.copy(dropboxLastSyncAt = now) }
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
