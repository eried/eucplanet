package com.eried.eucplanet.di

import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.data.repository.AccelSplitReset
import com.eried.eucplanet.data.repository.AndroidPhoneDevice
import com.eried.eucplanet.data.repository.AppHealthRepository
import com.eried.eucplanet.data.repository.DaoWheelProfiles
import com.eried.eucplanet.data.repository.DropboxLinkScratch
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.IncomingShareRepository
import com.eried.eucplanet.data.repository.LegalLockdownController
import com.eried.eucplanet.data.repository.LegalLockdownGate
import com.eried.eucplanet.data.repository.MetricsReset
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.PhoneImu
import com.eried.eucplanet.data.repository.PhoneSensorRepository
import com.eried.eucplanet.data.repository.RideRecording
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.SharedPrefsDropboxLinkScratch
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.repository.AndroidTripMeterFixes
import com.eried.eucplanet.data.repository.TripMeterFixes
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelProfiles
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.store.NavMarkerStore
import com.eried.eucplanet.data.store.OverlayPresetStore
import com.eried.eucplanet.data.store.ThemeStore
import com.eried.eucplanet.data.store.TripMeterStore
import com.eried.eucplanet.data.store.tripMeterDataStore
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.weather.WeatherRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Repositories, stores and sync. The explicit definitions hand a Lazy to the
 * constructor parameters that break a dependency cycle.
 */
val dataModule = module {
    singleOf(::SettingsRepository)
    // Explicit: the constructor's optional initial state (iOS seeds it) is not
    // a Koin dependency, and singleOf would try to resolve it.
    single { LegalLockdownController(get()) } bind LegalLockdownGate::class
    single {
        TripRepository(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            shareSession = inject(),
        )
    } bind RideRecording::class
    // WheelRepository lives in :shared and names its collaborators by small
    // interfaces (WheelRepositoryPorts.kt); the Android classes are bound to
    // them where they are defined.
    single<WheelProfiles> { DaoWheelProfiles(get()) }
    single<PhoneDevice> { AndroidPhoneDevice(androidContext()) }
    single {
        WheelRepository(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(),
            tripRepositoryLazy = inject(),
            appNotifier = get(),
            hornPlayer = get(),
            phone = get(),
        )
    }
    single {
        TripMeterRepository(
            store = get(),
            wheelRepositoryLazy = inject(),
            fixes = get(),
            settingsRepository = get(),
        )
    }
    single<TripMeterFixes> { AndroidTripMeterFixes(inject()) }
    single { TripMeterStore(androidContext().tripMeterDataStore) }
    singleOf(::NavMarkerStore)
    singleOf(::OverlayPresetStore)
    singleOf(::ThemeStore) { bind<com.eried.eucplanet.ui.theme.ThemeFiles>() }
    singleOf(::AccelSplitRepository) { bind<AccelSplitReset>() }
    singleOf(::AppHealthRepository)
    // DropboxRepository lives in :shared; the PKCE scratch is Android's SharedPreferences.
    single<DropboxLinkScratch> { SharedPrefsDropboxLinkScratch(androidContext()) }
    // Not singleOf: it would ask Koin for the optional test transport and crash at start.
    single { DropboxRepository(get(), get()) }
    singleOf(::IncomingShareRepository)
    singleOf(::MetricsReset)
    singleOf(::PhoneSensorRepository) { bind<PhoneImu>() }
    singleOf(::SyncManager)
    singleOf(::WeatherRepository)
}
