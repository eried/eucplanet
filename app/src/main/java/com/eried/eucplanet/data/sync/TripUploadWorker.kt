package com.eried.eucplanet.data.sync

import kotlinx.coroutines.flow.first
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Uploads any trip CSVs that haven't been synced yet, newest-first.
 * Enqueued after each recording ends and on manual retry.
 */
@HiltWorker
class TripUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tripDao: TripDao,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) : CoroutineWorker(context, params) {

    companion object { private const val TAG = "TripUploadWorker" }

    override suspend fun doWork(): Result = syncManager.withUploadPass {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            Log.i(TAG, "No sync folder configured, skipping")
            return@withUploadPass Result.success()
        }

        val pending = tripDao.getPendingUploads()

        // One listing for the whole pass. Asking the folder about each trip in
        // turn is what made mirroring a restored library crawl.
        val knownNames = syncManager.listFolderTripNames(settings)?.toSet()

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
            tripRepository.allTrips.first().filter { t ->
                t.endTime != null && t.fileName.lowercase() !in queued &&
                    t.fileName !in knownNames
            }
        }
        if (pending.isEmpty() && reconcile.isEmpty()) return@withUploadPass Result.success()
        if (reconcile.isNotEmpty()) Log.i(TAG, "Reconcile: folder is missing ${reconcile.size} trip(s)")

        var anyFailed = false
        for (trip in pending + reconcile.map { it.copy(uploadStatus = 4) }) {
            val file = tripRepository.getTripFile(trip)
            if (!file.exists()) {
                // Mark as uploaded anyway so it stops retrying forever
                tripDao.update(trip.copy(uploadStatus = 2, uploadedAt = System.currentTimeMillis()))
                continue
            }

            // Status 4 came from Dropbox: mirror it in, but never over a file
            // the folder already holds.
            val ok = syncManager.uploadCsv(
                settings, file,
                skipIfPresent = trip.uploadStatus == 4,
                knownNames = knownNames,
            )
            if (ok) {
                tripDao.update(trip.copy(
                    uploadStatus = 2,
                    uploadedAt = System.currentTimeMillis()
                ))
                Log.i(TAG, "Uploaded ${trip.fileName}")
            } else {
                tripDao.update(trip.copy(uploadStatus = 3))
                anyFailed = true
                Log.w(TAG, "Upload failed for ${trip.fileName}")
            }
        }

        if (anyFailed) {
            val attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0)
            val next = attempt + 1
            Log.i(TAG, "Scheduling retry attempt $next in ${SyncManager.delayForAttempt(next)}s")
            syncManager.scheduleTripUploadAttempt(next)
        }
        return@withUploadPass Result.success()
    }
}
