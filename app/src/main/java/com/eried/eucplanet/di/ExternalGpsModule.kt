package com.eried.eucplanet.di

import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.RaceBoxAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Multibinding registry for external-GPS BLE adapters. Each implementation
 * binds itself into a Set<ExternalGpsAdapter>; the scanner and connection
 * manager iterate the set without knowing the concrete classes. To add a
 * new family (Draggy, VBox, ...) drop another @Binds entry here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExternalGpsModule {

    @Binds
    @IntoSet
    abstract fun bindRaceBox(impl: RaceBoxAdapter): ExternalGpsAdapter
}
