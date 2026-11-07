package com.eried.eucplanet.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.repository.SettingsRepository

/**
 * Drains any trips that still need to be uploaded to EucStats.
 * Enqueued after each recording ends and on manual retry. The sweep itself is
 * the shared [sweepEucStatsUploads]; this schedules its retry chain.
 */
class EucStatsUploadWorker(
    ctx: Context,
    params: WorkerParameters,
    private val tripDao: TripDao,
    private val eucStatsRepository: EucStatsRepository,
    private val syncManager: SyncManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(ctx, params) {

    companion object { private const val TAG = "EucStatsUploadWorker" }

    override suspend fun doWork(): Result {
        val sweep = sweepEucStatsUploads(
            settings = settingsRepository.get(),
            // The riderStoreId StateFlow is read asynchronously, so on a cold-start
            // reconcile it may not be populated yet: re-read the file before giving up.
            riderStoreId = { syncManager.riderStoreId.value ?: syncManager.readRiderIdFile() },
            tripDao = tripDao,
            eucStatsRepository = eucStatsRepository,
            tag = TAG,
        )
        if (sweep.retry) {
            val attempt = inputData.getInt(SyncManager.KEY_ATTEMPT, 0)
            val next = attempt + 1
            Log.i(TAG, "Scheduling retry attempt $next in ${SyncManager.delayForAttempt(next)}s")
            syncManager.scheduleEucStatsUploadAttempt(next)
        }
        return Result.success()
    }
}
