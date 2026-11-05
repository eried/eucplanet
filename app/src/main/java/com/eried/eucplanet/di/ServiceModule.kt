package com.eried.eucplanet.di

import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.audio.HornPlayer
import com.eried.eucplanet.audio.SfxSidecar
import com.eried.eucplanet.cheats.CheatState
import com.eried.eucplanet.data.repository.AppNotices
import com.eried.eucplanet.data.repository.CheatFlags
import com.eried.eucplanet.data.repository.EventAnnouncer
import com.eried.eucplanet.data.repository.PhoneHorn
import com.eried.eucplanet.data.repository.RouteKeepAlive
import com.eried.eucplanet.data.repository.WheelAlarms
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.ui.common.ExperimentalBannerState
import com.eried.eucplanet.ui.theme.ThemeController
import com.eried.eucplanet.util.AppNotifier
import com.eried.eucplanet.voice.VoiceCommandController
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext

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
    } bind WheelAlarms::class
    singleOf(::AutomationManager)
    singleOf(::TonePlayer) { bind<RouteKeepAlive>() }
    single {
        VoiceService(
            get(), get(), get(), get(), get(),
            wheelRepositoryLazy = inject(),
            metricsResetLazy = inject(),
            tonePlayer = get(),
        )
    } bind EventAnnouncer::class
    single {
        VoiceCommandController(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            flicManager = inject(),
        )
    }
    singleOf(::FlicManager)
    singleOf(::AppNotifier) { bind<AppNotices>() }
    singleOf(::CheatState) { bind<CheatFlags>() }
    singleOf(::EngineSoundEngine)
    singleOf(::HornPlayer) { bind<PhoneHorn>() }
    singleOf(::SfxSidecar)
    single {
        val context = androidContext()
        ThemeController(get(), get()) {
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
    singleOf(::ExperimentalBannerState) { bind<com.eried.eucplanet.ui.dashboard.ExperimentalBannerFlag>() }
}
