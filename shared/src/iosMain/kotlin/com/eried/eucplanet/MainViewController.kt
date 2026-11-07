package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.app.IosAppGraph
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eried.eucplanet.app.IosDashboardHost
import com.eried.eucplanet.app.IosScanHost
import com.eried.eucplanet.app.IosSettingsHost
import com.eried.eucplanet.app.IosRecordingHost
import com.eried.eucplanet.ui.recording.RecordingScreen
import com.eried.eucplanet.app.rememberViewModel
import com.eried.eucplanet.ui.settings.SettingsScreen
import com.eried.eucplanet.ui.settings.FlicScreen
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.app.IosFlic
import com.eried.eucplanet.app.IosLocation
import com.eried.eucplanet.app.IosTrips
import com.eried.eucplanet.app.IosEucStats
import com.eried.eucplanet.ui.scan.ScanScreen
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.app.RideActivity
import com.eried.eucplanet.ui.dashboard.DashboardScreen
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eried.eucplanet.probe.ProbeScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/** Entry point the Swift host (iosApp/AppDelegate.swift) shows. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val link = IosAppGraph.link
    val wheel = IosAppGraph.wheel
    // For scripted runs on the simulator, which cannot tap:
    // SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:<id> xcrun simctl launch ...
    (NSProcessInfo.processInfo.environment["EUC_CONNECT"] as? String)?.let { wheel.connect(it) }
    MainScope().launch { IosAppGraph.seed() }
    // The phone's GPS for the dashboard and trips, and auto-record, as
    // WheelService starts them on Android.
    IosLocation.start()
    IosTrips.followWheel()
    // Leaderboard uploads at launch and on every return to the front.
    IosEucStats.start()
    // SIMCTL_CHILD_EUC_RECORD=20 records a 20 s trip, for scripted runs.
    (NSProcessInfo.processInfo.environment["EUC_RECORD"] as? String)?.toLongOrNull()?.let { secs ->
        MainScope().launch {
            IosTrips.startRecording()
            kotlinx.coroutines.delay(secs * 1000)
            IosTrips.stopRecording()
        }
    }
    RideActivity.follow(link, wheel.wheelData)
    // SIMCTL_CHILD_EUC_TAB=1 opens the old probe screen on its backup tab,
    // the wheel protocol probe kept for diagnostics.
    val tab = (NSProcessInfo.processInfo.environment["EUC_TAB"] as? String)?.toIntOrNull()
    val startScreen = NSProcessInfo.processInfo.environment["EUC_SCREEN"] as? String
    // SIMCTL_CHILD_EUC_SETTINGS_TAB=4 opens Settings on a section, as the
    // dashboard's shortcuts do (initialTabSectionKey).
    val settingsTab = (NSProcessInfo.processInfo.environment["EUC_SETTINGS_TAB"] as? String)?.toIntOrNull() ?: 0
    return ComposeUIViewController {
        if (tab != null) {
            ProbeScreen(link, wheel, tab)
        } else {
            // As MainActivity does: the resolved active theme drives everything.
            val theme = IosAppGraph.theme
            LaunchedEffect(Unit) { theme.ensureResolved() }
            val colors by theme.activeColors.collectAsState()
            EucPlanetTheme(colors = colors) {
                // A small screen stack until the shared navigation graph lands.
                // SIMCTL_CHILD_EUC_SCREEN=scan opens on the scan screen (also settings, flic, trips).
                var screen by remember { mutableStateOf(startScreen ?: "dashboard") }
                when (screen) {
                    "scan" -> {
                        // Owned by a store cleared when the screen leaves, so the
                        // view model's onCleared re-arms auto-connect, as on Android.
                        val store = remember { ViewModelStore() }
                        val scanVm = remember {
                            ViewModelProvider.create(
                                store,
                                viewModelFactory { initializer { IosAppGraph.scanViewModel() } },
                            )[ScanViewModel::class]
                        }
                        DisposableEffect(store) { onDispose { store.clear() } }
                        ScanScreen(
                            onDeviceSelected = { screen = "dashboard" },
                            onBack = { screen = "dashboard" },
                            viewModel = scanVm,
                            host = IosScanHost,
                        )
                    }
                    "settings" -> {
                        val settingsVm = rememberViewModel { IosAppGraph.settingsViewModel() }
                        SettingsScreen(
                            onBack = { screen = "dashboard" },
                            onNavigateToFlic = { screen = "flic" },
                            initialTab = settingsTab,
                            viewModel = settingsVm,
                            host = IosSettingsHost,
                        )
                    }
                    "trips" -> {
                        val recordingVm = rememberViewModel { IosAppGraph.recordingViewModel() }
                        RecordingScreen(
                            onBack = { screen = "dashboard" },
                            onOpenBackupSettings = { screen = "settings" },
                            viewModel = recordingVm,
                            host = IosRecordingHost,
                        )
                    }
                    "flic" -> {
                        val flicVm = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }
                        FlicScreen(onBack = { screen = "settings" }, viewModel = flicVm)
                    }
                    else -> {
                        val viewModel = remember { IosAppGraph.dashboardViewModel() }
                        val host = remember { IosDashboardHost() }
                        DashboardScreen(
                            onNavigateToScan = { screen = "scan" },
                            onNavigateToSettings = { screen = "settings" },
                            onNavigateToRecording = { screen = "trips" },
                            viewModel = viewModel,
                            host = host,
                        )
                    }
                }
            }
        }
    }
}
