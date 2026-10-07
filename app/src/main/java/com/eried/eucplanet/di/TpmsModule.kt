package com.eried.eucplanet.di

import com.eried.eucplanet.tpms.SettingsTpmsPairingStore
import com.eried.eucplanet.tpms.TpmsPairingStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TpmsModule {

    /** The app remembers a paired sensor in settings; tests remember nothing. */
    @Binds
    @Singleton
    abstract fun bindTpmsPairingStore(impl: SettingsTpmsPairingStore): TpmsPairingStore
}
