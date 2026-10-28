package com.eried.eucplanet.di

import com.eried.eucplanet.crews.CrewsPairDeps
import com.eried.eucplanet.crews.PairApi
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Crews pairing wiring.
 *
 * Separate from [eucStatsModule] because pairing deliberately does not go through the
 * compiled-in eucstats base url: the server it talks to comes off the scanned QR code, which
 * is what lets one build pair against a development server and against production without a
 * second APK. Everything else in the app keeps using the base url it always did.
 */
val crewsModule = module {
    singleOf(::PairApi)
    single {
        val syncManager: SyncManager = get()
        val settingsRepository: SettingsRepository = get()
        CrewsPairDeps(
            api = get(),
            storeIdProvider = { syncManager.riderStoreId.value },
            // Read when the screen opens rather than held: the rider may have just walked over to
            // Settings to turn it on because the app refused their code.
            developerMode = { runBlocking { settingsRepository.get().crewsDevServerEnabled } },
        )
    }
}
