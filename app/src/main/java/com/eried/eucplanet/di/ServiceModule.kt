package com.eried.eucplanet.di

import com.eried.eucplanet.audio.EngineAudioTrack
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.audio.HornPlayer
import com.eried.eucplanet.cheats.CheatState
import com.eried.eucplanet.data.repository.AppNotices
import com.eried.eucplanet.data.repository.CheatFlags
import com.eried.eucplanet.data.repository.EventAnnouncer
import com.eried.eucplanet.data.repository.PhoneHorn
import com.eried.eucplanet.data.repository.RouteKeepAlive
import com.eried.eucplanet.data.repository.WheelAlarms
import com.eried.eucplanet.flic.ActionExecutor
import com.eried.eucplanet.flic.AndroidFlicPairing
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.asAutomationWheel
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
            get(), get(), get(), get(), get(), get(),
            tripRepositoryProvider = inject(),
        )
    } bind WheelAlarms::class
    single<com.eried.eucplanet.service.AlarmOutputs> {
        com.eried.eucplanet.service.AndroidAlarmOutputs(androidContext(), get(), get(), get())
    }
    // The shared automations, over the wheel and Android's own media half.
    single<com.eried.eucplanet.service.AutomationWheel> {
        get<com.eried.eucplanet.data.repository.WheelRepository>().asAutomationWheel()
    }
    single<com.eried.eucplanet.service.MediaAutomation> {
        com.eried.eucplanet.service.AndroidMediaAutomation(androidContext(), get(), get())
    }
    single {
        val lockdown: com.eried.eucplanet.data.repository.LegalLockdownController = get()
        val trips: com.eried.eucplanet.data.repository.TripRepository = get()
        AutomationManager(
            wheel = get(),
            lockedDown = lockdown::isEngaged,
            location = {
                trips.currentLocation.value?.let {
                    com.eried.eucplanet.data.repository.PhoneLocation(it.latitude, it.longitude)
                }
            },
            media = get(),
        )
    }
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
    singleOf(::AndroidFlicPairing) bind com.eried.eucplanet.flic.FlicPairing::class
    single { com.eried.eucplanet.flic.AndroidMediaControls(androidContext()) } bind com.eried.eucplanet.flic.MediaControls::class
    single { com.eried.eucplanet.flic.AndroidPhoneControls(androidContext()) } bind com.eried.eucplanet.flic.PhoneControls::class
    single<com.eried.eucplanet.flic.VoiceListening> {
        val commands: com.eried.eucplanet.voice.VoiceCommandController = get()
        object : com.eried.eucplanet.flic.VoiceListening {
            override fun hasMicPermission() = commands.hasMicPermission()
            override fun listen() = commands.listen()
        }
    }
    singleOf(::ActionExecutor) bind com.eried.eucplanet.ui.dashboard.ActionDispatcher::class
    singleOf(::AppNotifier) { bind<AppNotices>() }
    singleOf(::CheatState) { bind<CheatFlags>() }
    single<com.eried.eucplanet.audio.EngineAudioOutput> { EngineAudioTrack(androidContext()) }
    single { EngineSoundEngine(get(), EngineAudioTrack.rawBank(androidContext())) }
    singleOf(::HornPlayer) { bind<PhoneHorn>() }
    single {
        val context = androidContext()
        ThemeController(get(), get()) {
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
    singleOf(::ExperimentalBannerState) { bind<com.eried.eucplanet.ui.dashboard.ExperimentalBannerFlag>() }
}
