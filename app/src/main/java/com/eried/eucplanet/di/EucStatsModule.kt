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

    /** Base URL for the eucstats API. */
    private const val EUCSTATS_BASE_URL = "https://api.eucstats.com/v1"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideEucStatsApi(client: OkHttpClient): EucStatsApiContract =
        EucStatsApi(client) { EUCSTATS_BASE_URL }

    /**
     * Attestation provider. Uses the stub implementation which the server
     * accepts while Play Integrity is not yet fully configured.
     * Swap [StubAttestation] for [PlayIntegrityAttestation] once a real
     * GCP project number is available via a build config field.
     */
    @Provides
    @Singleton
    @Suppress("UNUSED_PARAMETER")
    fun provideAttestation(@ApplicationContext context: Context): Attestation =
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
    ): EucStatsSettingsPort = object : EucStatsSettingsPort {
        override suspend fun get(): AppSettings = settingsRepository.get()
        override suspend fun update(settings: AppSettings) = settingsRepository.update(settings)
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
