package com.eried.evendarkerbot.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.eried.evendarkerbot.ui.dashboard.DashboardScreen
import com.eried.evendarkerbot.ui.dashboard.MetricDetailScreen
import com.eried.evendarkerbot.ui.dashboard.MetricType
import com.eried.evendarkerbot.ui.recording.RecordingScreen
import com.eried.evendarkerbot.ui.recording.RecordingViewModel
import com.eried.evendarkerbot.ui.recording.TripDetailScreen
import com.eried.evendarkerbot.ui.scan.ScanScreen
import com.eried.evendarkerbot.ui.settings.FlicScreen
import com.eried.evendarkerbot.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Scan : Screen("scan")
    data object Settings : Screen("settings")
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
    fun NavHostController.navigateSingle(route: String) =
        navigate(route) { launchSingleTop = true }
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None }
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToScan = { navController.navigateSingle(Screen.Scan.route) },
                onNavigateToSettings = { navController.navigateSingle(Screen.Settings.route) },
                onNavigateToRecording = { navController.navigateSingle(Screen.Recording.route) },
                onNavigateToMetric = { metric ->
                    navController.navigateSingle(Screen.MetricDetail.createRoute(metric))
                }
            )
        }
        composable(Screen.Scan.route) {
            ScanScreen(
                onDeviceSelected = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToFlic = { navController.navigateSingle(Screen.Flic.route) }
            )
        }
        composable(Screen.Flic.route) {
            FlicScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Recording.route) {
            RecordingScreen(
                onBack = { navController.popBackStack() },
                onViewTrip = { trip ->
                    navController.navigateSingle(Screen.TripDetail.createRoute(trip.id))
                }
            )
        }
        composable(
            Screen.TripDetail.route,
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId")
            val viewModel: RecordingViewModel = hiltViewModel()
            val trips by viewModel.trips.collectAsState()
            val trip = tripId?.let { id -> trips.find { it.id == id } }

            if (trip != null) {
                TripDetailScreen(
                    trip = trip,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            } else {
                LaunchedEffect(tripId) {
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
                    onBack = { navController.popBackStack() }
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
