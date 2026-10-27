package com.eried.eucplanet.di

import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.audio.HornPlayer
import com.eried.eucplanet.audio.SfxSidecar
import com.eried.eucplanet.cheats.CheatState
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.ui.common.ExperimentalBannerState
import com.eried.eucplanet.ui.theme.ThemeController
import com.eried.eucplanet.util.AppNotifier
import com.eried.eucplanet.voice.VoiceCommandController
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Process-wide services: alarms, voice, sound, buttons, theme. The explicit
 * definitions hand a Lazy to the constructor parameters that break a
 * dependency cycle.
 */
val serviceModule = module {
    single {
        AlarmEngine(
            get(), get(), get(), get(), get(), get(), get(), get(),
            tripRepositoryProvider = inject(),
        )
    }
    singleOf(::AutomationManager)
    singleOf(::TonePlayer)
    single {
        VoiceService(
            get(), get(), get(), get(), get(),
            wheelRepositoryLazy = inject(),
            metricsResetLazy = inject(),
            tonePlayer = get(),
        )
    }
    single {
        VoiceCommandController(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            flicManager = inject(),
        )
    }
    singleOf(::FlicManager)
    singleOf(::AppNotifier)
    singleOf(::CheatState)
    singleOf(::EngineSoundEngine)
    singleOf(::HornPlayer)
    singleOf(::SfxSidecar)
    singleOf(::ThemeController)
    singleOf(::ExperimentalBannerState)
}
