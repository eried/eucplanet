package com.eried.eucplanet.di

import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.WheelAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the active [WheelAdapter] implementation to [CompositeWheelAdapter],
 * which holds one sub-adapter per BLE-protocol family (InMotion V2, InMotion
 * V1, KingSong, Begode/Gotway, Veteran, Ninebot) and routes by BLE-advertised
 * name on connect.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindWheelAdapter(impl: CompositeWheelAdapter): WheelAdapter
}
