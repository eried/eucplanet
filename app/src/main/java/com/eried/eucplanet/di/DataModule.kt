package com.eried.eucplanet.di

import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.data.repository.AppHealthRepository
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.IncomingShareRepository
import com.eried.eucplanet.data.repository.LegalLockdownController
import com.eried.eucplanet.data.repository.MetricsReset
import com.eried.eucplanet.data.repository.PhoneSensorRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.store.NavMarkerStore
import com.eried.eucplanet.data.store.OverlayPresetStore
import com.eried.eucplanet.data.store.ThemeStore
import com.eried.eucplanet.data.store.TripMeterStore
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.weather.WeatherRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Repositories, stores and sync. The explicit definitions hand a Lazy to the
 * constructor parameters that break a dependency cycle.
 */
val dataModule = module {
    singleOf(::SettingsRepository)
    singleOf(::LegalLockdownController)
    single {
        TripRepository(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            shareSession = inject(),
        )
    }
    single {
        WheelRepository(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(),
            tripRepositoryLazy = inject(),
            appNotifier = get(),
            hornPlayer = get(),
        )
    }
    single {
        TripMeterRepository(
            store = get(),
            wheelRepositoryLazy = inject(),
            tripRepositoryLazy = inject(),
            settingsRepository = get(),
        )
    }
    singleOf(::TripMeterStore)
    singleOf(::NavMarkerStore)
    singleOf(::OverlayPresetStore)
    singleOf(::ThemeStore)
    singleOf(::AccelSplitRepository)
    singleOf(::AppHealthRepository)
    singleOf(::DropboxRepository)
    singleOf(::IncomingShareRepository)
    singleOf(::MetricsReset)
    singleOf(::PhoneSensorRepository)
    singleOf(::SyncManager)
    singleOf(::WeatherRepository)
}
