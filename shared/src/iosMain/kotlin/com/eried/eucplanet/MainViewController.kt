package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.app.IosAppGraph
import com.eried.eucplanet.app.IosDebugSnaps
import com.eried.eucplanet.app.IosDisplayPrefs
import com.eried.eucplanet.app.IosThemeEditor
import com.eried.eucplanet.app.StatusBarHost
import com.eried.eucplanet.app.IosAppHealth
import com.eried.eucplanet.app.IosLockdown
import com.eried.eucplanet.ui.lockdown.LegalLockdownScreen
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
import com.eried.eucplanet.ui.recording.EucViewerScreen
import com.eried.eucplanet.ui.recording.RecordingScreen
import com.eried.eucplanet.ui.recording.TripDetailScreen
import com.eried.eucplanet.app.IosEucViewerHost
import com.eried.eucplanet.app.IosTripDetailHost
import com.eried.eucplanet.app.rememberViewModel
import com.eried.eucplanet.app.IosCrashLog
import com.eried.eucplanet.ui.settings.SettingsScreen
import com.eried.eucplanet.ui.settings.FlicScreen
import com.eried.eucplanet.ui.settings.FlicViewModel
import com.eried.eucplanet.app.IosFlic
import com.eried.eucplanet.app.IosLocation
import com.eried.eucplanet.app.IosTrips
import com.eried.eucplanet.app.IosChargeAlerts
import com.eried.eucplanet.app.IosRideSession
import com.eried.eucplanet.app.IosChargingHost
import com.eried.eucplanet.ui.charging.ChargingMonitorLaunch
import com.eried.eucplanet.ui.charging.ChargingMonitorScreen
import com.eried.eucplanet.app.IosTripMeter
import com.eried.eucplanet.app.IosDropboxSync
import com.eried.eucplanet.app.IosEucStats
import com.eried.eucplanet.app.IosCrews
import com.eried.eucplanet.app.IosCrewsHost
import com.eried.eucplanet.crews.CrewsPairScreen
import androidx.compose.runtime.key
import com.eried.eucplanet.ui.scan.ScanScreen
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.app.RideActivity
import com.eried.eucplanet.ui.dashboard.DashboardScreen
import com.eried.eucplanet.ui.dashboard.TripMeterDetailScreen
import com.eried.eucplanet.ui.dashboard.MetricDetailRoute
import com.eried.eucplanet.ui.dashboard.MetricDetailScreen
import com.eried.eucplanet.ui.dashboard.MetricDetailViewModel
import com.eried.eucplanet.ui.dashboard.TripMeterDetailViewModel
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eried.eucplanet.probe.ProbeScreen
import kotlinx.coroutines.MainScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/** Entry point the Swift host (iosApp/AppDelegate.swift) shows. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    // Uncaught Kotlin exceptions to Documents/crashes, as Android's CrashHandler.
    IosCrashLog.install()
    val link = IosAppGraph.link
    val wheel = IosAppGraph.wheel
    // For scripted runs on the simulator, which cannot tap:
    // SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:<id> xcrun simctl launch ...
    (NSProcessInfo.processInfo.environment["EUC_CONNECT"] as? String)?.let { wheel.connect(it) }
    MainScope().launch {
        IosAppGraph.seed()
        // A recording the app was killed in, finalized or dropped, as on Android.
        IosTrips.recoverKilled()
        // Then the wheel out of any trip file never asked, as Android's start does.
        IosTrips.backfillWheelMeta()
    }
    // The phone's GPS for the dashboard and trips, as WheelService starts it
    // on Android, and lockdown stopping a recording.
    IosLocation.start()
    IosTrips.followWheel()
    // The trip meter counts while a wheel is connected, dashboard open or not.
    IosTripMeter.meter
    // Leaderboard uploads at launch and on every return to the front.
    IosEucStats.start()
    // The charge alerts, as WheelService posts them on Android.
    IosChargeAlerts.start()
    // What runs around a ride (automations, announcements, auto-record,
    // charge alerts, periodic reports): the shared RideSession, as WheelService runs it.
    IosRideSession.start()
    // Dropbox trips at launch, on every return to the front and while open.
    IosDropboxSync.start()
    // Keep screen on, and the language picker showing the applied language.
    IosDisplayPrefs.start()
    // The dashboard's "Needs attention" list, as MainActivity refreshes it on Android.
    IosAppHealth.start()
    // Legal Mode Lockdown: read before the first frame, and service mode
    // stopped while it runs, as EucPlanetApp does on Android.
    IosLockdown.start()
    // EUC_SNAP="15,20,100" saves a burst of window screenshots (field debugging).
    IosDebugSnaps.start()
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
    IosThemeEditor.start()
    val statusBar = StatusBarHost()
    val compose = ComposeUIViewController {
        if (tab != null) {
            ProbeScreen(link, wheel, tab)
        } else {
            // As MainActivity does: the resolved active theme drives everything.
            val theme = IosAppGraph.theme
            LaunchedEffect(Unit) { theme.ensureResolved() }
            val colors by theme.activeColors.collectAsState()
            // The status bar's text follows the theme: dark on a light theme.
            LaunchedEffect(colors.isLight) { statusBar.lightContent = !colors.isLight }
            EucPlanetTheme(colors = colors) {
              // The floating theme editor over every screen, as MainActivity mounts it.
              IosThemeEditor.Root {
                // A small screen stack until the shared navigation graph lands.
                // SIMCTL_CHILD_EUC_SCREEN=scan opens on the scan screen (also settings, flic, trips, trip, trip:<id>, viewer:<id>, tripmeter, metric:<key>, charging, charging:details).
                // "metric:VOLTAGE" opens a metric's detail, with the dashboard's route argument.
                var screen by remember { mutableStateOf(startScreen?.substringBefore(':') ?: "dashboard") }
                var metricArg by remember { mutableStateOf(startScreen?.substringAfter(':', "BATTERY") ?: "BATTERY") }
                // The trip the viewer shows ("trip:12"); blank opens the newest.
                var tripArg by remember { mutableStateOf(if (startScreen?.startsWith("trip:") == true) startScreen.substringAfter(':') else "") }
                // The trip View online shows ("viewer:12"), and where its back arrow goes.
                var viewerArg by remember { mutableStateOf(if (startScreen?.startsWith("viewer:") == true) startScreen.substringAfter(':') else "") }
                var viewerBack by remember { mutableStateOf("trips") }
                // Where the metric detail goes back to: the dashboard, or the charging monitor's history.
                var metricBack by remember { mutableStateOf("dashboard") }
                // Settings goes back to where it was opened from (the dashboard or the charging monitor).
                var settingsBack by remember { mutableStateOf("dashboard") }
                // "charging:details" opens the monitor with its details flyout up, as the battery icon's hold does.
                var chargingDetails by remember { mutableStateOf(startScreen == "charging:details") }
                // A charge alert's tap opens the monitor, as MainActivity does with ChargingMonitorLaunch.
                val chargingRequested by ChargingMonitorLaunch.pending.collectAsState()
                LaunchedEffect(chargingRequested) {
                    if (ChargingMonitorLaunch.consume()) {
                        chargingDetails = false
                        screen = "charging"
                    }
                }
                var leaderboardTab by remember { mutableStateOf(settingsTab) }
                // The crew pass sends a rider with no leaderboard profile to join one.
                val toLeaderboard by IosCrews.openLeaderboard.collectAsState()
                LaunchedEffect(toLeaderboard) {
                    if (toLeaderboard > 0) {
                        leaderboardTab = SETTINGS_TAB_CLOUD
                        screen = "settings"
                    }
                }
                // Legal Mode Lockdown replaces every screen, as MainActivity swaps
                // its whole graph: only the locked screen and the scan screen exist.
                val lockedDown by IosLockdown.controller.engaged.collectAsState()
                if (lockedDown) {
                    var lockScreen by remember { mutableStateOf("lockdown") }
                    if (lockScreen == "scan") {
                        val store = remember { ViewModelStore() }
                        val scanVm = remember {
                            ViewModelProvider.create(
                                store,
                                viewModelFactory { initializer { IosAppGraph.scanViewModel() } },
                            )[ScanViewModel::class]
                        }
                        DisposableEffect(store) { onDispose { store.clear() } }
                        ScanScreen(
                            onDeviceSelected = { lockScreen = "lockdown" },
                            onBack = { lockScreen = "lockdown" },
                            viewModel = scanVm,
                            host = IosScanHost,
                        )
                    } else {
                        val lockVm = rememberViewModel { IosLockdown.viewModel() }
                        LegalLockdownScreen(
                            onNavigateToScan = { lockScreen = "scan" },
                            viewModel = lockVm,
                            host = IosLockdown.Host,
                        )
                    }
                } else Box(Modifier.fillMaxSize()) {
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
                            onBack = { screen = settingsBack },
                            onNavigateToFlic = { screen = "flic" },
                            initialTab = leaderboardTab,
                            viewModel = settingsVm,
                            host = IosSettingsHost,
                        )
                    }
                    // One branch, so the trip list and the trip viewer share the view
                    // model, as NavGraph hands Recording's to TripDetail on Android.
                    "trips", "trip" -> {
                        val recordingVm = rememberViewModel { IosAppGraph.recordingViewModel() }
                        if (screen == "trips") {
                            RecordingScreen(
                                onBack = { screen = "dashboard" },
                                onViewTrip = { trip ->
                                    tripArg = trip.id.toString()
                                    screen = "trip"
                                },
                                onOpenBackupSettings = { screen = "settings" },
                                onViewOnline = { id ->
                                    viewerArg = id.toString()
                                    viewerBack = "trips"
                                    screen = "viewer"
                                },
                                viewModel = recordingVm,
                                host = IosRecordingHost,
                            )
                        } else {
                            // "trip:<id>", or "trip" alone for the newest trip.
                            val trips by recordingVm.trips.collectAsState()
                            val trip = tripArg.toLongOrNull()?.let { id -> trips.find { it.id == id } }
                                ?: if (tripArg.isBlank()) trips.firstOrNull() else null
                            if (trip != null) {
                                TripDetailScreen(
                                    trip = trip,
                                    onBack = { screen = "trips" },
                                    onViewOnline = { id ->
                                        viewerArg = id.toString()
                                        tripArg = trip.id.toString()
                                        viewerBack = "trip"
                                        screen = "viewer"
                                    },
                                    viewModel = recordingVm,
                                    host = IosTripDetailHost,
                                )
                            }
                        }
                    }
                    // View online: the EUC Viewer with the trip loaded (SIMCTL_CHILD_EUC_SCREEN=viewer:<id>).
                    "viewer" -> {
                        val viewerVm = rememberViewModel { IosAppGraph.eucViewerViewModel() }
                        key(viewerArg) {
                            EucViewerScreen(
                                tripId = viewerArg.toLongOrNull() ?: -1L,
                                onBack = { screen = viewerBack },
                                viewModel = viewerVm,
                                host = IosEucViewerHost,
                            )
                        }
                    }
                    "tripmeter" -> {
                        val tripMeterVm = rememberViewModel {
                            TripMeterDetailViewModel(IosTripMeter.meter, IosAppGraph.settings)
                        }
                        TripMeterDetailScreen(onBack = { screen = "dashboard" }, viewModel = tripMeterVm)
                    }
                    "metric" -> {
                        val metricVm = rememberViewModel {
                            MetricDetailViewModel(IosAppGraph.wheel, IosAppGraph.settings)
                        }
                        val route = remember(metricArg) { MetricDetailRoute.parse(metricArg) }
                        key(metricArg) {
                            MetricDetailScreen(
                                metricKeys = route.keys,
                                onBack = { screen = metricBack },
                                initialTabIndex = route.initialIndex,
                                viewModel = metricVm,
                            )
                        }
                    }
                    "charging" -> {
                        val chargingVm = rememberViewModel { IosAppGraph.chargingViewModel() }
                        key(chargingDetails) {
                            ChargingMonitorScreen(
                                onBack = { screen = "dashboard" },
                                onOpenHistory = {
                                    metricArg = "BATTERY"
                                    metricBack = "charging"
                                    screen = "metric"
                                },
                                onOpenSettings = {
                                    leaderboardTab = SETTINGS_TAB_CHARGING
                                    settingsBack = "charging"
                                    screen = "settings"
                                },
                                initialDetailsOpen = chargingDetails,
                                viewModel = chargingVm,
                                host = IosChargingHost,
                            )
                        }
                    }
                    "flic" -> {
                        val flicVm = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }
                        FlicScreen(
                            onBack = { screen = "settings" },
                            viewModel = flicVm,
                            voiceCommands = com.eried.eucplanet.ui.settings.SettingsRow.VOICE_COMMANDS !in IosSettingsHost.hiddenRows,
                        )
                    }
                    else -> {
                        val viewModel = remember { IosAppGraph.dashboardViewModel() }
                        // The GPS icon follows the location grant, which CoreLocation
                        // reports a moment after launch and whenever the rider changes it.
                        LaunchedEffect(viewModel) {
                            IosLocation.authorized.collect { viewModel.refreshLocationPermission() }
                        }
                        val host = remember { IosDashboardHost() }
                        DashboardScreen(
                            onNavigateToScan = { screen = "scan" },
                            onNavigateToSettings = { tab ->
                                leaderboardTab = tab ?: 0
                                settingsBack = "dashboard"
                                screen = "settings"
                            },
                            onNavigateToCharging = {
                                chargingDetails = false
                                screen = "charging"
                            },
                            onNavigateToChargingDetails = {
                                chargingDetails = true
                                screen = "charging"
                            },
                            onNavigateToRecording = { screen = "trips" },
                            onNavigateToTripMeter = { screen = "tripmeter" },
                            onNavigateToMetric = { metric ->
                                metricArg = metric
                                metricBack = "dashboard"
                                screen = "metric"
                            },
                            viewModel = viewModel,
                            host = host,
                        )
                    }
                }
                // Notices from code with no screen (AppNotifier), as MainActivity's
                // root snackbar host shows them over every screen but the locked one.
                val rootSnackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    IosAppGraph.notices.messages.collect { msg ->
                        Log.i("Notice", msg)
                        rootSnackbar.showSnackbar(msg)
                    }
                }
                SnackbarHost(
                    hostState = rootSnackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
                )
                }
                // The crew pass, over whatever is showing, as Android's own activity is.
                val pass by IosCrews.request.collectAsState()
                pass?.let { p ->
                    key(p.id) {
                        CrewsPairScreen(
                            deps = IosCrews.deps,
                            initialLink = p.link,
                            onDone = { IosCrews.close() },
                            host = IosCrewsHost,
                        )
                    }
                }
              }
            }
        }
    }
    statusBar.child = compose
    return statusBar
}

/** SettingsScreen's "Backups & leaderboards" section, LeaderboardSetupLaunch.SETTINGS_TAB_CLOUD on Android. */
private const val SETTINGS_TAB_CLOUD = 4

/** The section the charging monitor's Settings item opens, Screen.Settings.createRoute(9) on Android. */
private const val SETTINGS_TAB_CHARGING = 9
