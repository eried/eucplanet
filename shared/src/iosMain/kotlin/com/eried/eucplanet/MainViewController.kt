package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.app.IosAppGraph
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eried.eucplanet.app.IosDashboardHost
import com.eried.eucplanet.app.IosScanHost
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.action_back
import com.eried.eucplanet.resources.tab_alarms
import com.eried.eucplanet.resources.section_flic_buttons
import com.eried.eucplanet.ui.settings.FlicScreen
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.app.IosFlic
import com.eried.eucplanet.ui.settings.AlarmSettingsContent
import com.eried.eucplanet.ui.settings.AlarmViewModel
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
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
    RideActivity.follow(link, wheel.wheelData)
    // SIMCTL_CHILD_EUC_TAB=1 opens the old probe screen on its backup tab,
    // which still holds the scan list and the Dropbox settings sync until
    // those screens move.
    val tab = (NSProcessInfo.processInfo.environment["EUC_TAB"] as? String)?.toIntOrNull()
    val startScreen = NSProcessInfo.processInfo.environment["EUC_SCREEN"] as? String
    return ComposeUIViewController {
        if (tab != null) {
            ProbeScreen(link, wheel, tab)
        } else {
            // As MainActivity does: the resolved active theme drives everything.
            val theme = IosAppGraph.theme
            LaunchedEffect(Unit) { theme.ensureResolved() }
            val colors by theme.activeColors.collectAsState()
            EucPlanetTheme(colors = colors) {
                // A two-screen stack until the shared navigation graph lands.
                // SIMCTL_CHILD_EUC_SCREEN=scan opens on the scan screen (also alarms, flic).
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
                    // Settings is not shared yet; the probe screen holds the
                    // Dropbox settings sync until it moves.
                    // Settings is not shared yet: its alarms section is, and the
                    // probe's tab holds the Dropbox settings sync until it moves.
                    "probe" -> Column(Modifier.safeDrawingPadding()) {
                        Row {
                            TextButton(onClick = { screen = "dashboard" }) {
                                Text(stringResource(Res.string.action_back))
                            }
                            TextButton(onClick = { screen = "alarms" }) {
                                Text(stringResource(Res.string.tab_alarms))
                            }
                            TextButton(onClick = { screen = "flic" }) {
                                Text(stringResource(Res.string.section_flic_buttons))
                            }
                        }
                        ProbeScreen(link, wheel, 1)
                    }
                    "alarms" -> {
                        val store = remember { ViewModelStore() }
                        val alarmVm = remember {
                            ViewModelProvider.create(
                                store,
                                viewModelFactory { initializer { IosAppGraph.alarmViewModel() } },
                            )[AlarmViewModel::class]
                        }
                        DisposableEffect(store) { onDispose { store.clear() } }
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                TextButton(onClick = { screen = "probe" }) {
                                    Text(stringResource(Res.string.action_back))
                                }
                                AlarmSettingsContent(viewModel = alarmVm)
                            }
                        }
                    }
                    "flic" -> {
                        val store = remember { ViewModelStore() }
                        val flicVm = remember {
                            ViewModelProvider.create(
                                store,
                                viewModelFactory { initializer { FlicViewModel(IosAppGraph.settings, IosFlic) } },
                            )[FlicViewModel::class]
                        }
                        DisposableEffect(store) { onDispose { store.clear() } }
                        FlicScreen(onBack = { screen = "probe" }, viewModel = flicVm)
                    }
                    else -> {
                        val viewModel = remember { IosAppGraph.dashboardViewModel() }
                        val host = remember { IosDashboardHost() }
                        DashboardScreen(
                            onNavigateToScan = { screen = "scan" },
                            onNavigateToSettings = { screen = "probe" },
                            onNavigateToRecording = {},
                            viewModel = viewModel,
                            host = host,
                        )
                    }
                }
            }
        }
    }
}
