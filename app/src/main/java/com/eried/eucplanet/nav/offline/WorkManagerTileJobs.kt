package com.eried.eucplanet.nav.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Tile downloads in WorkManager: one unique job per square
 * ([TileDownloadWorker]), kept when queued again, retried with a growing
 * wait, and the weekly Wi-Fi check ([TileUpdateWorker]).
 */
class WorkManagerTileJobs(private val context: Context) : TileJobs {
    private val work get() = WorkManager.getInstance(context)

    override val progress: Flow<Map<String, Float>> =
        work.getWorkInfosByTagFlow(TileDownloadWorker.TAG_TILE).map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    val name = info.tags.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG)
                        ?: return@mapNotNull null
                    name to info.progress.getFloat(TileDownloadWorker.KEY_PROGRESS, 0f)
                }.toMap()
        }

    override fun enqueue(job: TileJob, unmeteredOnly: Boolean) {
        val net = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<TileDownloadWorker>()
            .setInputData(workDataOf(
                TileDownloadWorker.KEY_NAME to job.name,
                TileDownloadWorker.KEY_URL to job.url,
                TileDownloadWorker.KEY_LOOKUPS_URL to job.lookupsUrl,
                TileDownloadWorker.KEY_MAP_URL to job.mapUrl,
                TileDownloadWorker.KEY_ROUTE_SHARE to job.routeShare,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TileDownloadWorker.TAG_TILE)
            .addTag(NAME_TAG + job.name)
            .build()
        work.enqueueUniqueWork(uniqueName(job.name), ExistingWorkPolicy.KEEP, req)
    }

    override fun cancel(name: String) {
        work.cancelUniqueWork(uniqueName(name))
    }

    override fun scheduleAutoUpdate(enabled: Boolean) {
        if (!enabled) {
            work.cancelUniqueWork(AUTO_UPDATE)
            return
        }
        val req = PeriodicWorkRequestBuilder<TileUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        work.enqueueUniquePeriodicWork(AUTO_UPDATE, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun uniqueName(name: String) = "offline-tile-$name"

    private companion object {
        const val NAME_TAG = "tile:"
        const val AUTO_UPDATE = "offline-routing-auto-update"
    }
}
