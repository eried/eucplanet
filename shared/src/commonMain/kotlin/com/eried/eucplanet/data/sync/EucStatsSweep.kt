package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.Outcome
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.platform.Log

/**
 * When a finished trip, an app start or a manual retry should queue a
 * leaderboard upload: the rider has uploads on and a rider id to upload as.
 * The same rule on both phones; Android's TripRepository and the iPhone's
 * trip recorder ask it.
 */
fun eucStatsUploadsOn(settings: AppSettings, riderStoreId: String?): Boolean =
    settings.onlineUploadEnabled && riderStoreId != null

/** What one [sweepEucStatsUploads] pass ended as. */
sealed interface EucStatsSweep {
    /** Uploads are off: the retry chain ends. */
    data object Disabled : EucStatsSweep
    /** No rider id (folder unlinked / account deleted): the retry chain ends. */
    data object NoRider : EucStatsSweep
    data object NothingPending : EucStatsSweep
    data class Swept(val outcomes: List<Outcome>) : EucStatsSweep

    /** True when a trip is still waiting and another pass should be scheduled. */
    val retry: Boolean get() = this is Swept && workerResultRetry(outcomes)
}

/**
 * Drains any trips that still need to be uploaded to EucStats, after a
 * re-check of the held ones. Android's EucStatsUploadWorker runs it on
 * WorkManager; the iPhone runs it when a trip finishes and when the app comes
 * to the front. The caller schedules the next pass when [EucStatsSweep.retry].
 *
 * [riderStoreId] is read only once settings say uploads are on, and may read
 * the backing file, so a cold start whose id has not loaded yet still sweeps.
 */
suspend fun sweepEucStatsUploads(
    settings: AppSettings,
    riderStoreId: suspend () -> String?,
    tripDao: TripDao,
    eucStatsRepository: EucStatsRepository,
    tag: String = "EucStatsUploadWorker",
): EucStatsSweep {
    // Bail (kills the retry chain) when the prerequisites for online uploads
    // are gone. The rider id lives in the backup folder on Android, so
    // unlinking the folder, toggling leaderboards off, or deleting the account
    // all converge to "no rider id / disabled" and the sweep should stop, not loop.
    if (!settings.onlineUploadEnabled) {
        Log.i(tag, "Online uploads disabled; retry chain ends")
        return EucStatsSweep.Disabled
    }
    // The rider id is read asynchronously, so on a cold-start reconcile it may
    // not be populated yet. The caller re-reads the file before giving up,
    // otherwise the chain would bail and leave a queued trip stranded.
    if (riderStoreId() == null) {
        Log.i(tag, "No rider id (folder unlinked / account deleted); retry chain ends")
        return EucStatsSweep.NoRider
    }

    val pending = tripDao.getPendingEucstatsUploads()
    // Re-read the verdict of any trip the server is still holding. This runs before the
    // "nothing pending" bail on purpose: a held trip has already uploaded, so it is
    // never in `pending`, and its verdict can still change afterwards when a human
    // approves it. Without this the rider keeps an "under review" cloud forever on a
    // ride that is already counting. Failures here are ignored - it is a refresh, and
    // it must never turn into a retry loop or block the upload sweep.
    val cleared = runCatching { eucStatsRepository.refreshHeldTrips() }.getOrDefault(0)
    if (cleared > 0) Log.i(tag, "$cleared held trip(s) changed verdict")

    if (pending.isEmpty()) {
        Log.i(tag, "No pending eucstats uploads")
        return EucStatsSweep.NothingPending
    }

    val outcomes = mutableListOf<Outcome>()
    for (trip in pending) {
        val outcome = eucStatsRepository.uploadTrip(trip)
        outcomes += outcome
        when (outcome) {
            Outcome.UPLOADED -> Log.i(tag, "Uploaded trip ${trip.tripUuid}")
            Outcome.FAILED_PERMANENT -> Log.w(tag, "Permanent failure for trip ${trip.tripUuid}")
            Outcome.NEEDS_RETRY -> Log.w(tag, "Will retry trip ${trip.tripUuid}")
        }
    }
    return EucStatsSweep.Swept(outcomes)
}

/**
 * Pure decision function: returns true if the sweep should be retried.
 * Extracted so it can be unit-tested without Koin or Android dependencies.
 */
fun workerResultRetry(outcomes: List<Outcome>): Boolean =
    outcomes.any { it == Outcome.NEEDS_RETRY }
