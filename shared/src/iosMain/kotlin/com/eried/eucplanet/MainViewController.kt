package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.app.IosAppGraph
import com.eried.eucplanet.app.IosDashboardHost
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
    return ComposeUIViewController {
        if (tab != null) {
            ProbeScreen(link, wheel, tab)
        } else {
            EucPlanetTheme {
                var probe by remember { mutableStateOf(false) }
                if (probe) {
                    ProbeScreen(link, wheel, 0)
                } else {
                    val viewModel = remember { IosAppGraph.dashboardViewModel() }
                    val host = remember { IosDashboardHost() }
                    DashboardScreen(
                        // Scan and Settings are not shared yet; the probe screen
                        // stands in for both, so a wheel can still be picked.
                        onNavigateToScan = { probe = true },
                        onNavigateToSettings = { probe = true },
                        onNavigateToRecording = {},
                        viewModel = viewModel,
                        host = host,
                    )
                }
            }
        }
    }
}
