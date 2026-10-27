package com.eried.eucplanet.di

import android.os.Build
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.data.eucstats.Attestation
import com.eried.eucplanet.data.eucstats.EucStatsApi
import com.eried.eucplanet.data.eucstats.EucStatsApiContract
import com.eried.eucplanet.data.eucstats.EucStatsQualifiers
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.EucStatsSettingsPort
import com.eried.eucplanet.data.eucstats.PlayIntegrityAttestation
import com.eried.eucplanet.data.eucstats.StubAttestation
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.sync.SyncManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val eucStatsModule = module {

    single<OkHttpClient> { OkHttpClient.Builder().build() }

    single<EucStatsApiContract> { EucStatsApi(get()) { BuildConfig.EUCSTATS_API_BASE_URL } }

    /**
     * Attestation provider. Uses [PlayIntegrityAttestation] when a non-zero
     * [BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER] is configured; falls back to
     * [StubAttestation] (which the server accepts in stub mode) until the real
     * GCP project number is supplied.
     */
    single<Attestation> {
        if (BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER != 0L)
            PlayIntegrityAttestation(androidContext(), BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER)
        else
            StubAttestation()
    }

    /**
     * Bridge between [EucStatsSettingsPort] (Android-free interface used by
     * [EucStatsRepository]) and [SettingsRepository] (the singleton that
     * owns DataStore).
     */
    single<EucStatsSettingsPort> {
        val settingsRepository: SettingsRepository = get()
        val syncManager: SyncManager = get()
        object : EucStatsSettingsPort {
            override suspend fun get(): AppSettings = settingsRepository.get()
            override suspend fun update(settings: AppSettings) = settingsRepository.update(settings)
            override fun riderStoreId(): String? = syncManager.riderStoreId.value
            override suspend fun writeRiderId(storeId: String): Boolean =
                syncManager.writeRiderId(storeId)
            override suspend fun deleteRiderId(): Boolean = syncManager.deleteRiderIdFile()
        }
    }

    /**
     * Lambda that reads the raw CSV bytes for a trip from the local filesystem.
     * Injected as a qualified function so [EucStatsRepository] stays testable
     * without a real [TripRepository] or Android File access.
     */
    single<(TripRecord) -> ByteArray>(EucStatsQualifiers.TripFileBytes) {
        val tripRepository: TripRepository = get()
        val bytes: (TripRecord) -> ByteArray = { trip -> tripRepository.getTripFile(trip).readBytes() }
        bytes
    }

    factory(EucStatsQualifiers.AppVersion) { BuildConfig.VERSION_NAME }

    factory(EucStatsQualifiers.OsVersion) { "Android ${Build.VERSION.RELEASE}" }

    single<() -> Long>(EucStatsQualifiers.Clock) {
        val clock: () -> Long = { System.currentTimeMillis() }
        clock
    }

    single {
        EucStatsRepository(
            api = get(),
            attestation = get(),
            settings = get(),
            tripDao = get(),
            tripFileBytes = get(EucStatsQualifiers.TripFileBytes),
            appVersion = get(EucStatsQualifiers.AppVersion),
            osVersion = get(EucStatsQualifiers.OsVersion),
            clock = get(EucStatsQualifiers.Clock),
        )
    }
}
