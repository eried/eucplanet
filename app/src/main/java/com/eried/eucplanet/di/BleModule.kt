package com.eried.eucplanet.di

import com.eried.eucplanet.ble.InMotionV2Adapter
import com.eried.eucplanet.ble.WheelAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the active [WheelAdapter] implementation. Phase 1 hard-binds to
 * [InMotionV2Adapter] (V14 only). Later phases will replace this with a
 * GATT-based runtime selection: scan discovered services against a registry
 * to pick the right family adapter at connect time.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindWheelAdapter(impl: InMotionV2Adapter): WheelAdapter
}
