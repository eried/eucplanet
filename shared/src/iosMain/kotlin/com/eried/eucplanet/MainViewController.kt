package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.app.IosAppGraph
import com.eried.eucplanet.app.IosAppStart
import com.eried.eucplanet.app.IosDebugSnaps
import com.eried.eucplanet.app.IosLaunchHooks
import com.eried.eucplanet.app.IosLinks
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.share_link_invalid
import org.jetbrains.compose.resources.getString
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
import com.eried.eucplanet.ui.recording.RecordingScreen
import com.eried.eucplanet.ui.recording.TripDetailScreen
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
import com.eried.eucplanet.app.IosBackHandler
import com.eried.eucplanet.app.IosBackSwipeBridge
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
    // The services run already when the app was started without a screen
    // (a Bluetooth relaunch, AppDelegate.swift); then this only attaches to them.
    IosAppStart.start(bluetoothRelaunch = false)
    val link = IosAppGraph.link
    val wheel = IosAppGraph.wheel
    // SIMCTL_CHILD_EUC_DIALOG=service (or about) opens that dashboard dialog.
    (NSProcessInfo.processInfo.environment["EUC_DIALOG"] as? String)?.let {
        com.eried.eucplanet.ui.dashboard.DashboardDialogBus.open(it)
    }
    // SIMCTL_CHILD_EUC_NAV=1 (with EUC_SCREEN=route) starts turn-by-turn on the
    // planned stops once there is a fix, as the Start button does; =dashboard
    // then goes back to the dashboard, for the turn card over it.
    val navHook = NSProcessInfo.processInfo.environment["EUC_NAV"] as? String
    val autoNav = navHook == "1" || navHook == "dashboard"
    // SIMCTL_CHILD_EUC_TAB=1 opens the old probe screen on its backup tab,
    // the wheel protocol probe kept for diagnostics.
    val tab = (NSProcessInfo.processInfo.environment["EUC_TAB"] as? String)?.toIntOrNull()
    val startScreen = NSProcessInfo.processInfo.environment["EUC_SCREEN"] as? String
    // SIMCTL_CHILD_EUC_SCREEN_AFTER=20 starts on the dashboard and opens
    // EUC_SCREEN 20 s later, as a rider who opens it a while after the start.
    val screenAfterSecs = (NSProcessInfo.processInfo.environment["EUC_SCREEN_AFTER"] as? String)?.toIntOrNull() ?: 0
    // SIMCTL_CHILD_EUC_STUDIO_CAPTURE=photo|clip|take (with EUC_SCREEN=studio)
    // takes a photo, exports the replay clip or records a five second take
    // by itself once the studio settles, for scripted runs.
    // SIMCTL_CHILD_EUC_STUDIO_SHEET=add_element|manage_elements|layout|load_preset opens that sheet.
    val studioSheet = (NSProcessInfo.processInfo.environment["EUC_STUDIO_SHEET"] as? String)?.let { name ->
        com.eried.eucplanet.ui.studio.StudioScriptedSheet.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val studioCapture = (NSProcessInfo.processInfo.environment["EUC_STUDIO_CAPTURE"] as? String)?.let { name ->
        com.eried.eucplanet.ui.studio.StudioScriptedCapture.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    // SIMCTL_CHILD_EUC_SETTINGS_TAB=4 opens Settings on a section, as the
    // dashboard's shortcuts do (initialTabSectionKey).
    val settingsTab = (NSProcessInfo.processInfo.environment["EUC_SETTINGS_TAB"] as? String)?.toIntOrNull() ?: 0
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
                // The left-edge swipe as back for dialogs too (see IosBack.kt).
                IosBackSwipeBridge()
                // A small screen stack until the shared navigation graph lands.
                // Every screen but the dashboard registers IosBackHandler with
                // the same target as its back arrow, so the left-edge swipe goes
                // back as Android's system back does.
                // SIMCTL_CHILD_EUC_SCREEN=scan opens on the scan screen (also settings, flic, trips, trip, trip:<id>, tripmeter, metric:<key>, charging, charging:details, route, studio, studio:<trip id>).
                // "metric:VOLTAGE" opens a metric's detail, with the dashboard's route argument.
                var screen by remember {
                    mutableStateOf(if (screenAfterSecs > 0) "dashboard" else startScreen?.substringBefore(':') ?: "dashboard")
                }
                LaunchedEffect(Unit) {
                    if (screenAfterSecs > 0 && startScreen != null) {
                        kotlinx.coroutines.delay(screenAfterSecs * 1000L)
                        screen = startScreen.substringBefore(':')
                    }
                }
                var metricArg by remember { mutableStateOf(startScreen?.substringAfter(':', "BATTERY") ?: "BATTERY") }
                // The trip the viewer shows ("trip:12"); blank opens the newest.
                var tripArg by remember { mutableStateOf(if (startScreen?.startsWith("trip:") == true) startScreen.substringAfter(':') else "") }
                // The trip the Overlay Studio replays ("studio:12"), or none for live.
                var studioTrip by remember {
                    mutableStateOf(if (startScreen?.startsWith("studio:") == true) startScreen.substringAfter(':').toLongOrNull() else null)
                }
                // Where the studio's close goes back to.
                var studioBack by remember { mutableStateOf("dashboard") }
                // Where the metric detail goes back to: the dashboard, or the charging monitor's history.
                var metricBack by remember { mutableStateOf("dashboard") }
                // Settings goes back to where it was opened from (the dashboard, the charging monitor or the route builder).
                var settingsBack by remember { mutableStateOf("dashboard") }
                // The route builder's view model lives while the builder or the
                // Navigation settings it opens show, as its back stack entry does
                // on Android, and goes when the rider leaves it for the dashboard.
                val routeStore = remember { ViewModelStore() }
                DisposableEffect(routeStore) { onDispose { routeStore.clear() } }
                val leaveRoute = {
                    routeStore.clear()
                    screen = "dashboard"
                }
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
                // A share or route link opened from outside (IosLinks) goes to the
                // route builder, which asks the rider about it, as MainActivity does.
                val linkedPlace by IosAppGraph.incomingShares.pending.collectAsState()
                val linkedJoin by IosAppGraph.pendingShareJoin.pending.collectAsState()
                LaunchedEffect(linkedPlace, linkedJoin) {
                    if (linkedPlace != null || linkedJoin != null) screen = "route"
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
                        IosBackHandler { lockScreen = "lockdown" }
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
                        IosBackHandler { screen = "dashboard" }
                        ScanScreen(
                            onDeviceSelected = { screen = "dashboard" },
                            onBack = { screen = "dashboard" },
                            viewModel = scanVm,
                            host = IosScanHost,
                        )
                    }
                    "settings" -> {
                        val settingsVm = rememberViewModel { IosAppGraph.settingsViewModel() }
                        IosBackHandler { screen = settingsBack }
                        SettingsScreen(
                            onBack = { screen = settingsBack },
                            initialTab = leaderboardTab,
                            viewModel = settingsVm,
                            host = IosSettingsHost,
                        )
                    }
                    // One branch, so the trip list and the trip viewer share the view
                    // model, as NavGraph hands Recording's to TripDetail on Android.
                    "trips", "trip" -> {
                        val recordingVm = rememberViewModel { IosAppGraph.recordingViewModel() }
                        // Before the screen, so the screen's own handler (a trip
                        // import swallows back) is the newer one and wins.
                        IosBackHandler { screen = if (screen == "trips") "dashboard" else "trips" }
                        if (screen == "trips") {
                            RecordingScreen(
                                onBack = { screen = "dashboard" },
                                onViewTrip = { trip ->
                                    tripArg = trip.id.toString()
                                    screen = "trip"
                                },
                                onOpenBackupSettings = { screen = "settings" },
                                onReplayTrip = { id ->
                                    studioTrip = id
                                    studioBack = "trips"
                                    screen = "studio"
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
                                    onReplayTrip = { id ->
                                        tripArg = trip.id.toString()
                                        studioTrip = id
                                        studioBack = "trip"
                                        screen = "studio"
                                    },
                                    viewModel = recordingVm,
                                    host = IosTripDetailHost,
                                )
                            }
                        }
                    }
                    "tripmeter" -> {
                        val tripMeterVm = rememberViewModel {
                            TripMeterDetailViewModel(IosTripMeter.meter, IosAppGraph.settings)
                        }
                        IosBackHandler { screen = "dashboard" }
                        TripMeterDetailScreen(onBack = { screen = "dashboard" }, viewModel = tripMeterVm)
                    }
                    "metric" -> {
                        val metricVm = rememberViewModel {
                            MetricDetailViewModel(IosAppGraph.wheel, IosAppGraph.settings)
                        }
                        val route = remember(metricArg) { MetricDetailRoute.parse(metricArg) }
                        IosBackHandler { screen = metricBack }
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
                        IosBackHandler { screen = "dashboard" }
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
                    "route" -> {
                        val routeVm = remember(routeStore) {
                            ViewModelProvider.create(
                                routeStore,
                                viewModelFactory { initializer { IosAppGraph.routeBuilderViewModel() } },
                            )[com.eried.eucplanet.ui.navigator.RouteBuilderViewModel::class]
                        }
                        // The screen registers its own back (host.BackHandler), which wins.
                        IosBackHandler { leaveRoute() }
                        if (autoNav) LaunchedEffect(routeVm) {
                            // Once the stops are back and the phone has a fix.
                            while (routeVm.waypoints.value.isEmpty() || routeVm.currentLocation.value == null) {
                                kotlinx.coroutines.delay(500)
                            }
                            kotlinx.coroutines.delay(1_500)
                            if (!routeVm.navRunning.value) {
                                Log.i("RouteBuilder", "EUC_NAV: starting turn-by-turn")
                                routeVm.startNavigation(com.eried.eucplanet.data.model.NavMode.TURN_BY_TURN, force = true) {}
                            }
                            if (navHook == "dashboard") {
                                kotlinx.coroutines.delay(5_000)
                                leaveRoute()
                            }
                        }
                        com.eried.eucplanet.ui.navigator.RouteBuilderScreen(
                            onExit = { leaveRoute() },
                            onOpenNavSettings = {
                                leaderboardTab = SETTINGS_TAB_NAVIGATION
                                settingsBack = "route"
                                screen = "settings"
                            },
                            viewModel = routeVm,
                            host = com.eried.eucplanet.app.IosRouteBuilderHost,
                        )
                    }
                    // The Overlay Studio, live or replaying a trip (SIMCTL_CHILD_EUC_SCREEN=studio, studio:<trip id>).
                    "studio" -> {
                        val studioVm = rememberViewModel { IosAppGraph.overlayStudioViewModel() }
                        val studioHost = remember { com.eried.eucplanet.app.IosStudioHost() }
                        val leaveStudio = {
                            screen = studioBack
                            studioBack = "dashboard"
                        }
                        // The screen registers its own back (host.BackHandler) while it records, which wins.
                        IosBackHandler { leaveStudio() }
                        LaunchedEffect(studioVm) { applyStudioHooks(studioVm) }
                        key(studioTrip) {
                            com.eried.eucplanet.ui.studio.OverlayStudioScreen(
                                onBack = { leaveStudio() },
                                onOpenBackupSettings = {
                                    leaderboardTab = SETTINGS_TAB_CLOUD
                                    settingsBack = "studio"
                                    screen = "settings"
                                },
                                replayTripId = studioTrip,
                                viewModel = studioVm,
                                host = studioHost,
                                scriptedCapture = studioCapture,
                                scriptedSheet = studioSheet,
                            )
                        }
                    }
                    "flic" -> {
                        val flicVm = rememberViewModel { FlicViewModel(IosAppGraph.settings, IosFlic) }
                        IosBackHandler { screen = "settings" }
                        FlicScreen(
                            onBack = { screen = "settings" },
                            viewModel = flicVm,
                            voiceCommands = com.eried.eucplanet.ui.settings.SettingsRow.VOICE_COMMANDS !in IosSettingsHost.hiddenRows,
                            phoneVolume = com.eried.eucplanet.ui.settings.SettingsRow.PHONE_VOLUME !in IosSettingsHost.hiddenRows,
                            musicPlayer = IosSettingsHost.drivesMusicPlayer,
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
                            onNavigateToNavigator = { screen = "route" },
                            onNavigateToStudio = {
                                studioTrip = null
                                studioBack = "dashboard"
                                screen = "studio"
                            },
                            viewModel = viewModel,
                            host = host,
                        )
                    }
                }
                // The turn cue popup over every screen, as MainActivity mounts it;
                // the route builder shows the cue on its own map instead.
                val overlayVm = rememberViewModel { IosAppGraph.navigationOverlayViewModel() }
                com.eried.eucplanet.ui.navigator.NavigationOverlay(
                    onOpenMap = { screen = "route" },
                    suppressOnPhone = screen == "route",
                    viewModel = overlayVm,
                )
                // Notices from code with no screen (AppNotifier), as MainActivity's
                // root snackbar host shows them over every screen but the locked one.
                val rootSnackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    IosAppGraph.notices.messages.collect { msg ->
                        Log.i("Notice", msg)
                        rootSnackbar.showSnackbar(msg)
                    }
                }
                // A share link that did not read (IosLinks), said once this host listens.
                val badShare by IosLinks.badShare.collectAsState()
                LaunchedEffect(badShare) {
                    if (IosLinks.consumeBadShare()) {
                        IosAppGraph.notices.post(getString(Res.string.share_link_invalid))
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
                        // Last composed, so the edge swipe closes the pass before
                        // it reaches the screen under it, as Android's back
                        // finishes the pass activity.
                        IosBackHandler { IosCrews.close() }
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

/** The section the route builder's settings item opens, Screen.Settings.createRoute(8) on Android. */
private const val SETTINGS_TAB_NAVIGATION = 8

/** The section the charging monitor's Settings item opens, Screen.Settings.createRoute(9) on Android. */
private const val SETTINGS_TAB_CHARGING = 9

/**
 * The studio's launch hooks, for scripted runs: SIMCTL_CHILD_EUC_STUDIO_PRESET
 * loads a starter ("Race") or, failing that, a saved preset of that name;
 * EUC_STUDIO_FORMAT picks the clip format (GIF, APNG, MP4) and
 * EUC_STUDIO_PHOTO the photo format (PNG, JPG, WEBP), saved like the rider's
 * pick; EUC_STUDIO_SCALE the export size in percent (25 to 100).
 */
private fun applyStudioHooks(vm: com.eried.eucplanet.ui.studio.OverlayStudioViewModel) {
    val env = NSProcessInfo.processInfo.environment
    (env["EUC_STUDIO_PRESET"] as? String)?.let { name ->
        vm.loadBundledPreset(name) { ok ->
            if (!ok) vm.loadPreset(name) { found -> Log.i("OverlayStudio", "EUC_STUDIO_PRESET $name: saved=$found") }
            else Log.i("OverlayStudio", "EUC_STUDIO_PRESET $name: starter")
        }
    }
    (env["EUC_STUDIO_FORMAT"] as? String)?.let {
        vm.setReplayVideoFormat(com.eried.eucplanet.ui.studio.ReplayVideoFormat.fromKey(it.uppercase()))
    }
    (env["EUC_STUDIO_PHOTO"] as? String)?.let {
        vm.setReplayPhotoFormat(com.eried.eucplanet.ui.studio.ReplayPhotoFormat.fromKey(it.uppercase()))
    }
    (env["EUC_STUDIO_SCALE"] as? String)?.toIntOrNull()?.let { vm.setReplayScale(it.coerceIn(25, 100)) }
}
