package com.eried.evendarkerbot.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.eried.evendarkerbot.ui.recording.TripViewerScreen
import com.eried.evendarkerbot.ui.scan.ScanScreen
import com.eried.evendarkerbot.ui.settings.FlicScreen
import com.eried.evendarkerbot.ui.settings.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

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
    data object TripViewer : Screen("trip_viewer/{fileName}") {
        fun createRoute(fileName: String) = "trip_viewer/${URLEncoder.encode(fileName, "UTF-8")}"
    }
}

// Temporary storage for base64 data (too large for nav arguments)
internal object ViewerDataHolder {
    var dbbBase64: String? = null
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToScan = { navController.navigate(Screen.Scan.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToRecording = { navController.navigate(Screen.Recording.route) },
                onNavigateToMetric = { metric ->
                    navController.navigate(Screen.MetricDetail.createRoute(metric))
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
                onNavigateToFlic = { navController.navigate(Screen.Flic.route) }
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
                    navController.navigate(Screen.TripDetail.createRoute(trip.id))
                },
                onOpenViewer = { dbbBase64, fileName ->
                    ViewerDataHolder.dbbBase64 = dbbBase64
                    navController.navigate(Screen.TripViewer.createRoute(fileName))
                }
            )
        }
        composable(
            Screen.TripDetail.route,
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            val viewModel: RecordingViewModel = hiltViewModel()
            val trips by viewModel.trips.collectAsState()
            val trip = trips.find { it.id == tripId }

            if (trip != null) {
                TripDetailScreen(
                    trip = trip,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        composable(
            Screen.MetricDetail.route,
            arguments = listOf(navArgument("metric") { type = NavType.StringType })
        ) { backStackEntry ->
            val metricName = backStackEntry.arguments?.getString("metric") ?: return@composable
            val metricType = try { MetricType.valueOf(metricName) } catch (_: Exception) { return@composable }
            MetricDetailScreen(
                metricType = metricType,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Screen.TripViewer.route,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType })
        ) { backStackEntry ->
            val fileName = URLDecoder.decode(
                backStackEntry.arguments?.getString("fileName") ?: "trips.dbb", "UTF-8"
            )
            val dbbBase64 = ViewerDataHolder.dbbBase64
            if (dbbBase64 != null) {
                TripViewerScreen(
                    dbbBase64 = dbbBase64,
                    fileName = fileName,
                    onBack = {
                        ViewerDataHolder.dbbBase64 = null
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
