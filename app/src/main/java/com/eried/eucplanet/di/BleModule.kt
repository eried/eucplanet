package com.eried.eucplanet.di

import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.WheelAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the active [WheelAdapter] as a [CompositeWheelAdapter], which holds
 * one sub-adapter per BLE-protocol family (InMotion V2, InMotion V1, KingSong,
 * Begode/Gotway, Veteran, Ninebot) and routes by BLE-advertised name on connect.
 *
 * The adapter family now lives in `:shared` (commonMain), so it has no Hilt
 * annotations of its own; CompositeWheelAdapter constructs its sub-adapters
 * internally and we just hand Hilt a single instance here.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideWheelAdapter(): WheelAdapter = CompositeWheelAdapter()
}
