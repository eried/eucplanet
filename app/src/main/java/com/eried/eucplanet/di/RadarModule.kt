package com.eried.eucplanet.di

import com.eried.eucplanet.ble.radar.RadarAdapter
import com.eried.eucplanet.ble.radar.VariaAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Multibinding registry for rear-view radar BLE adapters. Each implementation
 * binds itself into a Set<RadarAdapter>; the scanner and connection manager
 * iterate the set without knowing the concrete classes. To add a new family
 * (Magicshine Seemee, BSafe, …) drop another @Binds entry here.
 *
 * Mirrors [ExternalGpsModule] on purpose so adding radars looks like adding
 * external-GPS boxes already does in this codebase.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RadarModule {

    @Binds
    @IntoSet
    abstract fun bindVaria(impl: VariaAdapter): RadarAdapter
}
