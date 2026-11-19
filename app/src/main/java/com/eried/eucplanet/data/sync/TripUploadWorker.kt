package com.eried.eucplanet.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Uploads any trip CSVs that haven't been synced yet, newest-first.
 * Enqueued after each recording ends and on manual retry.
 *
 * The pass itself is the shared [BackupFolderSync.uploadPass] (the iPhone
 * runs the same one): under the pass lock, the queue plus
 * every trip the folder is missing, skip-if-present for trips that came from
 * Dropbox, and the conflict count on every pass. This worker only carries
 * the retry curve.
 */
class TripUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(context, params) {

    companion object { private const val TAG = "TripUploadWorker" }

    override suspend fun doWork(): Result {
        val anyFailed = !syncManager.folderSync.uploadPass()
        if (anyFailed) {
            val attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0)
            val next = attempt + 1
            Log.i(TAG, "Scheduling retry attempt $next in ${SyncManager.delayForAttempt(next)}s")
            syncManager.scheduleTripUploadAttempt(next)
        }
        return Result.success()
    }
}
