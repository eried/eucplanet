package com.eried.eucplanet.di

import com.eried.eucplanet.tpms.SettingsTpmsPairingStore
import com.eried.eucplanet.tpms.TpmsPairingStore
import com.eried.eucplanet.tpms.TpmsRepository
import com.eried.eucplanet.tpms.TpmsScanner
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val tpmsModule = module {
    /** The app remembers a paired sensor in settings; tests remember nothing. */
    singleOf(::SettingsTpmsPairingStore) { bind<TpmsPairingStore>() }
    // Explicit: the no-argument constructor is for tests.
    single { TpmsRepository(get<TpmsPairingStore>()) }
    singleOf(::TpmsScanner)
}
