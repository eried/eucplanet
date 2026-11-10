package com.eried.eucplanet.di

import com.eried.eucplanet.diagnostics.AndroidDiagnosticsPlatform
import com.eried.eucplanet.diagnostics.DiagnosticsPlatform
import com.eried.eucplanet.diagnostics.WheelDiagnosticsViewModel
import com.eried.eucplanet.ui.charging.AndroidChargingCsvFiles
import com.eried.eucplanet.ui.charging.ChargingCsvFiles
import com.eried.eucplanet.ui.charging.ChargingMonitorViewModel
import com.eried.eucplanet.ui.dashboard.ActiveRoute
import com.eried.eucplanet.ui.dashboard.AndroidActiveRoute
import com.eried.eucplanet.ui.dashboard.AndroidAppHealth
import com.eried.eucplanet.ui.dashboard.AndroidDashboardPlatform
import com.eried.eucplanet.ui.dashboard.AndroidDashboardSync
import com.eried.eucplanet.ui.dashboard.AndroidDashboardTrips
import com.eried.eucplanet.ui.dashboard.AndroidDashboardVoice
import com.eried.eucplanet.ui.dashboard.AndroidDashboardVoiceCommands
import com.eried.eucplanet.ui.dashboard.AndroidMetricsResetter
import com.eried.eucplanet.ui.dashboard.AndroidTripMeterFeed
import com.eried.eucplanet.ui.dashboard.AppHealth
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardPlatform
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.dashboard.DashboardTrips
import com.eried.eucplanet.ui.dashboard.DashboardViewModel
import com.eried.eucplanet.ui.dashboard.DashboardVoice
import com.eried.eucplanet.ui.dashboard.DashboardVoiceCommands
import com.eried.eucplanet.ui.dashboard.MetricsResetter
import com.eried.eucplanet.ui.dashboard.TripMeterFeed
import com.eried.eucplanet.ui.dashboard.MetricDetailViewModel
import com.eried.eucplanet.ui.dashboard.TripMeterDetailViewModel
import com.eried.eucplanet.ui.dashboard.sources.DataSourcesViewModel
import com.eried.eucplanet.ui.lockdown.LegalLockdownViewModel
import com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel
import com.eried.eucplanet.ui.radar.RadarOverlayViewModel
import com.eried.eucplanet.ui.recording.EucViewerViewModel
import com.eried.eucplanet.ui.recording.AndroidRecordingOnline
import com.eried.eucplanet.ui.recording.AndroidRecordingPlatform
import com.eried.eucplanet.ui.recording.AndroidRecordingTrips
import com.eried.eucplanet.ui.recording.RecordingOnline
import com.eried.eucplanet.ui.recording.RecordingPlatform
import com.eried.eucplanet.ui.recording.RecordingTrips
import com.eried.eucplanet.ui.recording.RecordingViewModel
import com.eried.eucplanet.ui.scan.AndroidScanPlatform
import com.eried.eucplanet.ui.scan.AndroidWheelScanner
import com.eried.eucplanet.ui.scan.ScanPlatform
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.ui.scan.WheelScanner
import com.eried.eucplanet.ui.settings.AndroidEngineSoundPreview
import com.eried.eucplanet.ui.settings.AndroidHornSound
import com.eried.eucplanet.ui.settings.AndroidHudSettingsLink
import com.eried.eucplanet.ui.settings.ControllerLockdownSwitch
import com.eried.eucplanet.ui.settings.AndroidOnlineLeaderboard
import com.eried.eucplanet.ui.settings.AndroidPromptTones
import com.eried.eucplanet.ui.settings.AndroidSettingsPlatform
import com.eried.eucplanet.ui.settings.AndroidSettingsSync
import com.eried.eucplanet.ui.settings.AndroidSettingsTrips
import com.eried.eucplanet.ui.settings.AndroidSettingsVoice
import com.eried.eucplanet.ui.settings.AndroidSplitTimes
import com.eried.eucplanet.ui.settings.AndroidWatchLinks
import com.eried.eucplanet.ui.settings.EngineSoundPreview
import com.eried.eucplanet.ui.settings.HornSound
import com.eried.eucplanet.ui.settings.HudSettingsLink
import com.eried.eucplanet.ui.settings.LockdownSwitch
import com.eried.eucplanet.ui.settings.OnlineLeaderboard
import com.eried.eucplanet.ui.settings.PromptTones
import com.eried.eucplanet.ui.settings.SettingsAutomation
import com.eried.eucplanet.ui.settings.SettingsPlatform
import com.eried.eucplanet.ui.settings.SettingsSync
import com.eried.eucplanet.ui.settings.SettingsTrips
import com.eried.eucplanet.ui.settings.SettingsVoice
import com.eried.eucplanet.ui.settings.SplitTimes
import com.eried.eucplanet.ui.settings.WatchLinks
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.settings.ExternalGpsViewModel
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.ui.settings.RadarViewModel
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.settings.offline.OfflineDataViewModel
import com.eried.eucplanet.ui.settings.TpmsViewModel
import com.eried.eucplanet.ui.studio.OverlayStudioViewModel
import com.eried.eucplanet.ui.theme.ThemeEditorViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Every screen's ViewModel, created per ViewModelStoreOwner by koinViewModel(). */
val viewModelModule = module {
    // The shared DashboardViewModel's ports, each the Android class behind it.
    singleOf(::AndroidDashboardTrips) bind DashboardTrips::class
    singleOf(::AndroidTripMeterFeed) bind TripMeterFeed::class
    singleOf(::AndroidDashboardVoice) bind DashboardVoice::class
    singleOf(::AndroidDashboardVoiceCommands) bind DashboardVoiceCommands::class
    // The light and lock automations are the shared AutomationManager itself.
    single<DashboardAutomation> { get<com.eried.eucplanet.service.AutomationManager>() }
    singleOf(::AndroidDashboardSync) bind DashboardSync::class
    singleOf(::AndroidAppHealth) bind AppHealth::class
    singleOf(::AndroidMetricsResetter) bind MetricsResetter::class
    singleOf(::AndroidActiveRoute) bind ActiveRoute::class
    single<DashboardPlatform> { AndroidDashboardPlatform(androidContext(), get(), get(), get()) }
    // And the scan screen's.
    singleOf(::AndroidWheelScanner) bind WheelScanner::class
    single<ScanPlatform> { AndroidScanPlatform(androidContext()) }
    // And the settings screen's.
    singleOf(::AndroidSettingsVoice) bind SettingsVoice::class
    singleOf(::AndroidPromptTones) bind PromptTones::class
    singleOf(::AndroidSettingsTrips) bind SettingsTrips::class
    singleOf(::AndroidSettingsSync) bind SettingsSync::class
    single<SettingsAutomation> { get<com.eried.eucplanet.service.AutomationManager>() }
    singleOf(::AndroidWatchLinks) bind WatchLinks::class
    singleOf(::AndroidEngineSoundPreview) bind EngineSoundPreview::class
    singleOf(::AndroidHornSound) bind HornSound::class
    singleOf(::AndroidSplitTimes) bind SplitTimes::class
    singleOf(::ControllerLockdownSwitch) bind LockdownSwitch::class
    singleOf(::AndroidOnlineLeaderboard) bind OnlineLeaderboard::class
    singleOf(::AndroidHudSettingsLink) bind HudSettingsLink::class
    single<SettingsPlatform> { AndroidSettingsPlatform(androidContext(), get()) }
    singleOf(::AndroidRecordingTrips) bind RecordingTrips::class
    singleOf(::AndroidRecordingOnline) bind RecordingOnline::class
    single<RecordingPlatform> { AndroidRecordingPlatform(androidContext(), get()) }

    single<DiagnosticsPlatform> { AndroidDiagnosticsPlatform(androidContext(), get()) }
    viewModelOf(::WheelDiagnosticsViewModel)
    single<ChargingCsvFiles> { AndroidChargingCsvFiles(androidContext()) }
    viewModelOf(::ChargingMonitorViewModel)
    viewModelOf(::DashboardViewModel)
    viewModelOf(::MetricDetailViewModel)
    viewModelOf(::TripMeterDetailViewModel)
    viewModelOf(::DataSourcesViewModel)
    viewModelOf(::LegalLockdownViewModel)
    viewModelOf(::NavigationOverlayViewModel)
    viewModelOf(::RouteBuilderViewModel)
    viewModelOf(::RadarOverlayViewModel)
    viewModelOf(::EucViewerViewModel)
    viewModelOf(::RecordingViewModel)
    viewModelOf(::ScanViewModel)
    viewModelOf(::AlarmViewModel)
    viewModelOf(::ExternalGpsViewModel)
    viewModelOf(::FlicViewModel)
    viewModelOf(::RadarViewModel)
    viewModelOf(::OfflineDataViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::TpmsViewModel)
    viewModelOf(::OverlayStudioViewModel)
    viewModelOf(::ThemeEditorViewModel)
}
