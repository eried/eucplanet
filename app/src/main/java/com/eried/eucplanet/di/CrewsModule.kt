package com.eried.eucplanet.di

import com.eried.eucplanet.crews.CrewsPairDeps
import com.eried.eucplanet.crews.PairApi
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Crews pairing wiring.
 *
 * Separate from [EucStatsModule] because pairing deliberately does not go through the
 * compiled-in eucstats base url: the server it talks to comes off the scanned QR code, which
 * is what lets one build pair against a development server and against production without a
 * second APK. Everything else in the app keeps using the base url it always did.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrewsModule {

    @Provides
    @Singleton
    fun providePairApi(client: OkHttpClient): PairApi = PairApi(client)

    @Provides
    @Singleton
    fun provideCrewsPairDeps(
        api: PairApi,
        syncManager: SyncManager,
        settingsRepository: SettingsRepository,
    ): CrewsPairDeps = CrewsPairDeps(
        api = api,
        storeIdProvider = { syncManager.riderStoreId.value },
        // Read when the screen opens rather than held: the rider may have just walked over to
        // Settings to turn it on because the app refused their code.
        developerMode = { runBlocking { settingsRepository.get().crewsDevServerEnabled } },
    )
}
