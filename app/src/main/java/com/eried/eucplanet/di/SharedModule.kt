package com.eried.eucplanet.di

import com.eried.eucplanet.ble.CompositeWheelAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hands Hilt the singletons that now live in the shared module's Koin graph
 * ([SharedKoin]), so injected classes keep receiving the same instances.
 */
@Module
@InstallIn(SingletonComponent::class)
object SharedModule {
    @Provides
    @Singleton
    fun compositeWheelAdapter(): CompositeWheelAdapter = SharedKoin.koin.get()
}
