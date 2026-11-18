package com.eried.eucplanet.di

import com.eried.eucplanet.diagnostics.WheelDiagnosticsViewModel
import com.eried.eucplanet.ui.charging.ChargingMonitorViewModel
import com.eried.eucplanet.ui.dashboard.DashboardViewModel
import com.eried.eucplanet.ui.dashboard.MetricDetailViewModel
import com.eried.eucplanet.ui.dashboard.TripMeterDetailViewModel
import com.eried.eucplanet.ui.dashboard.sources.DataSourcesViewModel
import com.eried.eucplanet.ui.lockdown.LegalLockdownViewModel
import com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel
import com.eried.eucplanet.ui.radar.RadarOverlayViewModel
import com.eried.eucplanet.ui.recording.EucViewerViewModel
import com.eried.eucplanet.ui.recording.RecordingViewModel
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.settings.ExternalGpsViewModel
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.ui.settings.RadarViewModel
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.settings.offline.OfflineDataViewModel
import com.eried.eucplanet.ui.settings.TpmsViewModel
import com.eried.eucplanet.ui.studio.OverlayStudioViewModel
import com.eried.eucplanet.ui.theme.ThemeEditorViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Every screen's ViewModel, created per ViewModelStoreOwner by koinViewModel(). */
val viewModelModule = module {
    viewModelOf(::WheelDiagnosticsViewModel)
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
    // 24 parameters, past the 22 viewModelOf takes.
    viewModel {
        SettingsViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
    viewModelOf(::TpmsViewModel)
    viewModelOf(::OverlayStudioViewModel)
    viewModelOf(::ThemeEditorViewModel)
}
