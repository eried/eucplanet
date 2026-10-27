package com.eried.eucplanet.di

import com.eried.eucplanet.data.sync.DropboxSyncWorker
import com.eried.eucplanet.data.sync.EucStatsUploadWorker
import com.eried.eucplanet.data.sync.TripUploadWorker
import com.eried.eucplanet.widget.WeatherWidgetWorker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

/**
 * WorkManager workers, built by the KoinWorkerFactory that EucPlanetApp hands
 * WorkManager. Context and WorkerParameters come from the factory call.
 */
val workerModule = module {
    workerOf(::DropboxSyncWorker)
    workerOf(::EucStatsUploadWorker)
    workerOf(::TripUploadWorker)
    workerOf(::WeatherWidgetWorker)
}
