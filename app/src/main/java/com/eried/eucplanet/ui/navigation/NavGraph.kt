package com.eried.eucplanet.ui.navigation

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.eried.eucplanet.ui.charging.ChargingMonitorScreen
import com.eried.eucplanet.ui.dashboard.DashboardScreen
import com.eried.eucplanet.ui.dashboard.MetricDetailScreen
import com.eried.eucplanet.ui.navigator.RouteBuilderScreen
import com.eried.eucplanet.ui.recording.EucViewerScreen
import com.eried.eucplanet.ui.recording.RecordingScreen
import com.eried.eucplanet.ui.recording.RecordingViewModel
import com.eried.eucplanet.ui.recording.TripDetailScreen
import com.eried.eucplanet.ui.scan.ScanScreen
import com.eried.eucplanet.ui.settings.FlicScreen
import com.eried.eucplanet.ui.studio.OverlayStudioScreen
import com.eried.eucplanet.ui.settings.SettingsScreen
import com.eried.eucplanet.util.MultipleEventsCutter

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Scan : Screen("scan")
    data object Settings : Screen("settings?tab={tab}") {
        fun createRoute(tab: Int?) = if (tab == null) "settings" else "settings?tab=$tab"
    }
    data object Recording : Screen("recording")
    data object OverlayStudio : Screen("overlay_studio?tripId={tripId}") {
        fun createRoute(tripId: Long?) =
            if (tripId == null) "overlay_studio" else "overlay_studio?tripId=$tripId"
    }
    data object EucViewer : Screen("euc_viewer/{tripId}") {
        fun createRoute(tripId: Long) = "euc_viewer/$tripId"
    }
    data object RouteBuilder : Screen("route_builder")
    data object Flic : Screen("flic")
    data object TripDetail : Screen("trip_detail/{tripId}") {
        fun createRoute(tripId: Long) = "trip_detail/$tripId"
    }
    data object MetricDetail : Screen("metric_detail/{metric}") {
        fun createRoute(metric: String) = "metric_detail/$metric"
    }
    data object ChargingMonitor : Screen("charging_monitor")
}

@Composable
fun NavGraph(navController: NavHostController) {
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, dest, args ->
            Log.i("EucNav", "→ ${dest.route} args=$args")
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    val navCutter = remember { MultipleEventsCutter() }
    fun NavHostController.navigateSingle(route: String) {
        val entry = currentBackStackEntry ?: return
        if (entry.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        navCutter.processEvent { navigate(route) { launchSingleTop = true } }
    }
    fun NavHostController.popSingle() {
        val entry = currentBackStackEntry ?: return
        if (entry.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        navCutter.processEvent { popBackStack() }
    }
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToScan = { navController.navigateSingle(Screen.Scan.route) },
                onNavigateToSettings = { tab ->
                    navController.navigateSingle(Screen.Settings.createRoute(tab))
                },
                onNavigateToRecording = { navController.navigateSingle(Screen.Recording.route) },
                onNavigateToStudio = {
                    navController.navigateSingle(Screen.OverlayStudio.createRoute(null))
                },
                onNavigateToNavigator = { navController.navigateSingle(Screen.RouteBuilder.route) },
                onNavigateToFlic = { navController.navigateSingle(Screen.Settings.createRoute(7)) },
                onNavigateToTripDetail = { tripId ->
                    navController.navigateSingle(Screen.TripDetail.createRoute(tripId))
                },
                onNavigateToMetric = { metric ->
                    navController.navigateSingle(Screen.MetricDetail.createRoute(metric))
                },
                onNavigateToCharging = {
                    navController.navigateSingle(Screen.ChargingMonitor.route)
                }
            )
        }
        composable(Screen.Scan.route) {
            ScanScreen(
                onDeviceSelected = { navController.popSingle() },
                onBack = { navController.popSingle() }
            )
        }
        composable(
            Screen.Settings.route,
            arguments = listOf(navArgument("tab") {
                type = NavType.IntType
                defaultValue = 0
            })
        ) { backStackEntry ->
            val tab = backStackEntry.arguments?.getInt("tab") ?: 0
            SettingsScreen(
                onBack = { navController.popSingle() },
                onNavigateToFlic = { navController.navigateSingle(Screen.Flic.route) },
                initialTab = tab
            )
        }
        composable(Screen.Flic.route) {
            FlicScreen(
                onBack = { navController.popSingle() }
            )
        }
        composable(
            Screen.OverlayStudio.route,
            arguments = listOf(navArgument("tripId") {
                type = NavType.LongType
                defaultValue = -1L
            })
        ) { backStackEntry ->
            val tid = backStackEntry.arguments?.getLong("tripId") ?: -1L
            OverlayStudioScreen(
                onBack = { navController.popSingle() },
                onOpenBackupSettings = {
                    navController.navigateSingle(Screen.Settings.createRoute(4))
                },
                replayTripId = if (tid >= 0L) tid else null
            )
        }
        composable(
            Screen.EucViewer.route,
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            EucViewerScreen(
                tripId = backStackEntry.arguments?.getLong("tripId") ?: -1L,
                onBack = { navController.popSingle() }
            )
        }
        composable(Screen.RouteBuilder.route) {
            RouteBuilderScreen(
                onExit = { navController.popSingle() },
                onOpenNavSettings = {
                    navController.navigateSingle(Screen.Settings.createRoute(8))
                }
            )
        }
        composable(Screen.Recording.route) {
            RecordingScreen(
                onBack = { navController.popSingle() },
                onViewTrip = { trip ->
                    navController.navigateSingle(Screen.TripDetail.createRoute(trip.id))
                },
                onOpenBackupSettings = {
                    navController.navigateSingle(Screen.Settings.createRoute(4))
                },
                onViewOnline = { id ->
                    navController.navigateSingle(Screen.EucViewer.createRoute(id))
                },
                onReplayTrip = { id ->
                    navController.navigateSingle(Screen.OverlayStudio.createRoute(id))
                }
            )
        }
        composable(
            Screen.TripDetail.route,
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId")
            val recordingEntry = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Screen.Recording.route) }.getOrNull()
            }
            val viewModel: RecordingViewModel =
                if (recordingEntry != null) hiltViewModel(recordingEntry) else hiltViewModel()
            val trips by viewModel.trips.collectAsState()
            val trip = tripId?.let { id -> trips.find { it.id == id } }
            var notFound by remember { mutableStateOf(false) }

            LaunchedEffect(tripId, trips) {
                if (tripId == null) {
                    notFound = true
                } else if (trips.isNotEmpty() && trips.none { it.id == tripId }) {
                    val fromDb = viewModel.getTripById(tripId)
                    if (fromDb == null) notFound = true
                }
            }

            if (trip != null) {
                TripDetailScreen(
                    trip = trip,
                    onBack = { navController.popSingle() },
                    onViewOnline = { id ->
                        navController.navigateSingle(Screen.EucViewer.createRoute(id))
                    },
                    onReplayTrip = { id ->
                        navController.navigateSingle(Screen.OverlayStudio.createRoute(id))
                    },
                    viewModel = viewModel
                )
            }
            if (notFound) {
                LaunchedEffect(Unit) {
                    Log.w("EucNav", "TripDetail: trip $tripId not found, popping back")
                    navController.popBackStack()
                }
            }
        }
        composable(
            Screen.MetricDetail.route,
            arguments = listOf(navArgument("metric") { type = NavType.StringType })
        ) { backStackEntry ->
            val metricName = backStackEntry.arguments?.getString("metric")
            if (metricName.isNullOrBlank()) {
                LaunchedEffect(metricName) {
                    Log.w("EucNav", "MetricDetail: blank metric key, popping back")
                    navController.popBackStack()
                }
            } else {
                // Route argument shape:
                //   "BATTERY"                  → single tab
                //   "SPEED,BATTERY,POWER"      → 3 tabs, default initial 0
                //   "SPEED,BATTERY,POWER|2"    → 3 tabs, pre-select POWER
                // The "|<index>" suffix is optional and lets the composite
                // tile side-tap pre-select a specific tab WITHOUT changing
                // the tab order — riders see the same strip regardless of
                // which sub-tile they tapped on the dashboard.
                val (keysPart, idxPart) = metricName.split("|", limit = 2)
                    .let { if (it.size == 2) it[0] to it[1] else metricName to "0" }
                val keys = keysPart.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val initialIdx = idxPart.toIntOrNull()?.coerceIn(0, (keys.size - 1).coerceAtLeast(0)) ?: 0
                MetricDetailScreen(
                    metricKeys = keys,
                    initialTabIndex = initialIdx,
                    onBack = { navController.popSingle() }
                )
            }
        }
        composable(Screen.ChargingMonitor.route) {
            ChargingMonitorScreen(
                onBack = { navController.popSingle() },
                onOpenHistory = { navController.navigateSingle(Screen.MetricDetail.createRoute("BATTERY")) },
                onOpenSettings = { navController.navigateSingle(Screen.Settings.createRoute(9)) }
            )
        }
    }
}
