package com.eried.eucplanet.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.Outcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains any trips that still need to be uploaded to EucStats.
 * Enqueued after each recording ends and on manual retry.
 */
@HiltWorker
class EucStatsUploadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val tripDao: TripDao,
    private val eucStatsRepository: EucStatsRepository,
) : CoroutineWorker(ctx, params) {

    companion object { private const val TAG = "EucStatsUploadWorker" }

    override suspend fun doWork(): Result {
        val pending = tripDao.getPendingEucstatsUploads()
        if (pending.isEmpty()) {
            Log.i(TAG, "No pending eucstats uploads")
            return Result.success()
        }

        val outcomes = mutableListOf<Outcome>()
        for (trip in pending) {
            val outcome = eucStatsRepository.uploadTrip(trip)
            outcomes += outcome
            when (outcome) {
                Outcome.UPLOADED -> Log.i(TAG, "Uploaded trip ${trip.tripUuid}")
                Outcome.FAILED_PERMANENT -> Log.w(TAG, "Permanent failure for trip ${trip.tripUuid}")
                Outcome.NEEDS_RETRY -> Log.w(TAG, "Will retry trip ${trip.tripUuid}")
            }
        }

        return if (workerResultRetry(outcomes)) Result.retry() else Result.success()
    }
}

/**
 * Pure decision function: returns true if the worker should be retried.
 * Extracted so it can be unit-tested without Hilt or Android dependencies.
 */
internal fun workerResultRetry(outcomes: List<Outcome>): Boolean =
    outcomes.any { it == Outcome.NEEDS_RETRY }
