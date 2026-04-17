package com.eried.eucplanet.data.sync

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

    override suspend fun doWork(): Result {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            Log.i(TAG, "No sync folder configured, skipping")
            return Result.success()
        }

        val pending = tripDao.getPendingUploads()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (trip in pending) {
            val file = tripRepository.getTripFile(trip)
            if (!file.exists()) {
                // Mark as uploaded anyway so it stops retrying forever
                tripDao.update(trip.copy(uploadStatus = 2, uploadedAt = System.currentTimeMillis()))
                continue
            }

            val ok = syncManager.uploadCsv(settings, file)
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

        return if (anyFailed) Result.retry() else Result.success()
    }
}
