package com.eried.eucplanet.di

import com.eried.eucplanet.ble.gps.DragyAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager
import com.eried.eucplanet.ble.gps.ExternalGpsLink
import com.eried.eucplanet.ble.gps.ExternalGpsScan
import com.eried.eucplanet.ble.gps.ExternalGpsScanner
import com.eried.eucplanet.ble.gps.RaceBoxAdapter
import com.eried.eucplanet.data.repository.ExternalGpsFeed
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import com.eried.eucplanet.util.AppForeground
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Registry for external-GPS BLE adapters. Each implementation is listed in
 * one Set<ExternalGpsAdapter>; the scanner and connection manager iterate the
 * set without knowing the concrete classes. To add a new family (Draggy,
 * VBox, ...) add it to the set here.
 */
val externalGpsModule = module {
    singleOf(::RaceBoxAdapter)
    singleOf(::DragyAdapter)
    // A fresh set per injection, as the multibinding was; its members are the
    // singletons above.
    factory<Set<ExternalGpsAdapter>>(DiQualifiers.ExternalGpsAdapters) {
        linkedSetOf(get<RaceBoxAdapter>(), get<DragyAdapter>())
    }
    // The shared repository and view model reach the Android BLE classes
    // through ExternalGpsLink and ExternalGpsScan.
    singleOf(::ExternalGpsConnectionManager) bind ExternalGpsLink::class
    single { ExternalGpsScanner(androidContext(), get(DiQualifiers.ExternalGpsAdapters)) } bind ExternalGpsScan::class
    single {
        // Lazy to break the dependency cycles: TripRepository and
        // WheelRepository inject us.
        val trips = inject<TripRepository>()
        val wheel = inject<WheelRepository>()
        val settings = get<SettingsRepository>()
        ExternalGpsRepository(
            settingsRepository = settings,
            link = get(),
            adapters = get(DiQualifiers.ExternalGpsAdapters),
            phoneFix = { trips.value.currentLocation.value?.toPhoneLocation() },
            alarmEngineProvider = inject(),
            foreground = AppForeground.isForeground,
            rideRunning = { com.eried.eucplanet.service.WheelService.isRunning },
            wheelSpeedKmh = { ExternalGpsRepository.rawWheelSpeedKmh(wheel.value, settings) },
        )
    } bind ExternalGpsFeed::class
}
