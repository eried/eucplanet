package com.eried.eucplanet.di

import com.eried.eucplanet.ble.gps.DragyAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager
import com.eried.eucplanet.ble.gps.ExternalGpsScanner
import com.eried.eucplanet.ble.gps.RaceBoxAdapter
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
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
    singleOf(::ExternalGpsConnectionManager)
    single { ExternalGpsScanner(androidContext(), get(DiQualifiers.ExternalGpsAdapters)) }
    single {
        ExternalGpsRepository(
            settingsRepository = get(),
            connectionManager = get(),
            adapters = get(DiQualifiers.ExternalGpsAdapters),
            tripRepositoryProvider = inject(),
            alarmEngineProvider = inject(),
        )
    }
}
