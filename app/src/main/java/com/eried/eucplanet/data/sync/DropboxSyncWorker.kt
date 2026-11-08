package com.eried.eucplanet.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository

/**
 * Mirror local trips + settings into the linked Dropbox App Folder, on
 * WorkManager. The pass itself is shared with the iPhone
 * ([DropboxTripSync.backgroundPass]): this worker keeps it to one at a time
 * and hands it the job's stop flag and attempt number.
 */
class DropboxSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val dropboxRepository: DropboxRepository,
    private val syncManager: SyncManager,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DropboxSyncWorker"
    }

    override suspend fun doWork(): Result {
        // One Dropbox pass at a time. The periodic job, the one-off job and a
        // rider's Sync all are separate entries to WorkManager and the app, and
        // two of them walking the same list uploaded every trip twice.
        if (syncManager.syncRunning.value) {
            Log.i(TAG, "Sync all is running; trying again later")
            return Result.retry()
        }
        if (!syncManager.dropboxPassLock.tryLock()) {
            Log.i(TAG, "Another Dropbox pass is running; skipping")
            return Result.success()
        }
        return try { pass() } finally { syncManager.dropboxPassLock.unlock() }
    }

    /** The shared pass ([DropboxTripSync.backgroundPass]); retries schedule themselves. */
    private suspend fun pass(): Result =
        when (syncManager.dropboxTrips.backgroundPass(
            attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0),
            isStopped = { isStopped },
            foregroundRunning = { syncManager.syncRunning.value },
        )) {
            BackgroundOutcome.RETRY -> Result.retry()
            BackgroundOutcome.DONE -> Result.success()
        }
}
