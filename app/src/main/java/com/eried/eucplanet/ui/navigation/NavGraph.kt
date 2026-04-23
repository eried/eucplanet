package com.eried.eucplanet.ui.navigation

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
import com.eried.eucplanet.ui.dashboard.DashboardScreen
import com.eried.eucplanet.ui.dashboard.MetricDetailScreen
import com.eried.eucplanet.ui.dashboard.MetricType
import com.eried.eucplanet.ui.recording.RecordingScreen
import com.eried.eucplanet.ui.recording.RecordingViewModel
import com.eried.eucplanet.ui.recording.TripDetailScreen
import com.eried.eucplanet.ui.scan.ScanScreen
import com.eried.eucplanet.ui.settings.FlicScreen
import com.eried.eucplanet.ui.settings.SettingsScreen
import com.eried.eucplanet.util.MultipleEventsCutter

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Scan : Screen("scan")
    data object Settings : Screen("settings?tab={tab}") {
        fun createRoute(tab: Int?) = if (tab == null) "settings" else "settings?tab=$tab"
    }
    data object Recording : Screen("recording")
    data object Flic : Screen("flic")
    data object TripDetail : Screen("trip_detail/{tripId}") {
        fun createRoute(tripId: Long) = "trip_detail/$tripId"
    }
    data object MetricDetail : Screen("metric_detail/{metric}") {
        fun createRoute(metric: String) = "metric_detail/$metric"
    }
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
                onNavigateToFlic = { navController.navigateSingle(Screen.Settings.createRoute(7)) },
                onNavigateToTripDetail = { tripId ->
                    navController.navigateSingle(Screen.TripDetail.createRoute(tripId))
                },
                onNavigateToMetric = { metric ->
                    navController.navigateSingle(Screen.MetricDetail.createRoute(metric))
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
        composable(Screen.Recording.route) {
            RecordingScreen(
                onBack = { navController.popSingle() },
                onViewTrip = { trip ->
                    navController.navigateSingle(Screen.TripDetail.createRoute(trip.id))
                },
                onOpenBackupSettings = {
                    navController.navigateSingle(Screen.Settings.createRoute(4))
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
            val metricType = metricName?.let {
                try { MetricType.valueOf(it) } catch (_: Exception) { null }
            }
            if (metricType != null) {
                MetricDetailScreen(
                    metricType = metricType,
                    onBack = { navController.popSingle() }
                )
            } else {
                LaunchedEffect(metricName) {
                    Log.w("EucNav", "MetricDetail: invalid metric '$metricName', popping back")
                    navController.popBackStack()
                }
            }
        }
    }
}
