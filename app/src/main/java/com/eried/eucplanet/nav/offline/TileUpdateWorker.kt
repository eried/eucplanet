package com.eried.eucplanet.nav.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** The weekly Wi-Fi check in WorkManager: the shared [OfflineTiles.autoUpdate],
 *  tried again later when the server cannot be read. */
class TileUpdateWorker(
    context: Context,
    params: WorkerParameters,
    private val tiles: OfflineTiles,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = if (tiles.autoUpdate()) Result.success() else Result.retry()
}
