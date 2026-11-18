package com.eried.eucplanet.di

import com.eried.eucplanet.ble.radar.RadarAdapter
import com.eried.eucplanet.ble.radar.RadarConnectionManager
import com.eried.eucplanet.ble.radar.RadarScanner
import com.eried.eucplanet.ble.radar.VariaAdapter
import com.eried.eucplanet.data.repository.RadarRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Registry for rear-view radar BLE adapters. Each implementation is listed in
 * one Set<RadarAdapter>; the scanner and connection manager iterate the set
 * without knowing the concrete classes. To add a new family (Magicshine
 * Seemee, BSafe, ...) add it to the set here.
 *
 * Mirrors [externalGpsModule] on purpose so adding radars looks like adding
 * external-GPS boxes already does in this codebase.
 */
val radarModule = module {
    singleOf(::VariaAdapter)
    // A fresh set per injection, as the multibinding was; its members are the
    // singletons above.
    factory<Set<RadarAdapter>>(DiQualifiers.RadarAdapters) { linkedSetOf(get<VariaAdapter>()) }
    singleOf(::RadarConnectionManager)
    single { RadarScanner(androidContext(), get(DiQualifiers.RadarAdapters)) }
    single {
        RadarRepository(
            settingsRepository = get(),
            connectionManager = get(),
            adapters = get(DiQualifiers.RadarAdapters),
            alarmEngine = get(),
        )
    }
}
