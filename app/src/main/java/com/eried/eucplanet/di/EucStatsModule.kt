package com.eried.eucplanet.di

import android.content.Context
import android.os.Build
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.data.eucstats.Attestation
import com.eried.eucplanet.data.eucstats.EucStatsApi
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import com.eried.eucplanet.data.eucstats.EucStatsAppVersion
import com.eried.eucplanet.data.eucstats.EucStatsClock
import com.eried.eucplanet.data.eucstats.EucStatsOsVersion
import com.eried.eucplanet.data.eucstats.EucStatsSettingsPort
import com.eried.eucplanet.data.eucstats.EucStatsTripFileBytes
import com.eried.eucplanet.data.eucstats.PlayIntegrityAttestation
import com.eried.eucplanet.data.eucstats.StubAttestation
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EucStatsModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideEucStatsApi(client: OkHttpClient): EucStatsApiContract =
        EucStatsApi(client) { BuildConfig.EUCSTATS_API_BASE_URL }

    /**
     * Attestation provider. Uses [PlayIntegrityAttestation] when a non-zero
     * [BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER] is configured; falls back to
     * [StubAttestation] (which the server accepts in stub mode) until the real
     * GCP project number is supplied.
     */
    @Provides
    @Singleton
    fun provideAttestation(@ApplicationContext context: Context): Attestation =
        if (BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER != 0L)
            PlayIntegrityAttestation(context, BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER)
        else
            StubAttestation()

    /**
     * Bridge between [EucStatsSettingsPort] (Android-free interface used by
     * [EucStatsRepository]) and [SettingsRepository] (the Hilt singleton that
     * owns DataStore).
     */
    @Provides
    @Singleton
    fun provideEucStatsSettingsPort(
        settingsRepository: SettingsRepository,
        syncManager: com.eried.eucplanet.data.sync.SyncManager,
    ): EucStatsSettingsPort = object : EucStatsSettingsPort {
        override suspend fun get(): AppSettings = settingsRepository.get()
        override suspend fun update(settings: AppSettings) = settingsRepository.update(settings)
        override fun riderStoreId(): String? = syncManager.riderStoreId.value
        override suspend fun writeRiderId(storeId: String): Boolean =
            syncManager.writeRiderId(storeId)
        override suspend fun deleteRiderId(): Boolean = syncManager.deleteRiderIdFile()
    }

    /**
     * Lambda that reads the raw CSV bytes for a trip from the local filesystem.
     * Injected as a qualified function so [EucStatsRepository] stays testable
     * without a real [TripRepository] or Android File access.
     */
    @Provides
    @Singleton
    @EucStatsTripFileBytes
    fun provideTripFileBytes(tripRepository: TripRepository): @JvmSuppressWildcards (TripRecord) -> ByteArray =
        { trip -> tripRepository.getTripFile(trip).readBytes() }

    @Provides
    @EucStatsAppVersion
    fun provideEucStatsAppVersion(): String = BuildConfig.VERSION_NAME

    @Provides
    @EucStatsOsVersion
    fun provideEucStatsOsVersion(): String = "Android ${Build.VERSION.RELEASE}"

    @Provides
    @Singleton
    @EucStatsClock
    fun provideEucStatsClock(): @JvmSuppressWildcards () -> Long = { System.currentTimeMillis() }
}
