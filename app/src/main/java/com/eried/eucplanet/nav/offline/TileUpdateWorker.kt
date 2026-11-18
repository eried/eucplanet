package com.eried.eucplanet.nav.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eried.eucplanet.data.repository.SettingsRepository

/** The weekly Wi-Fi check: queues a download for every tile the server has
 *  a newer build of, on Wi-Fi only. Runs only while the rider has it on. */
class TileUpdateWorker(
    context: Context,
    params: WorkerParameters,
    private val tiles: OfflineTiles,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.currentOrLoad().offlineRouting.wifiAutoUpdate) return Result.success()
        if (tiles.store.downloaded().isEmpty()) return Result.success()
        val remote = tiles.remote() ?: return Result.retry()
        tiles.download(tiles.outdated(remote), unmeteredOnly = true)
        return Result.success()
    }
}
