package com.eried.eucplanet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eried.eucplanet.data.model.ActionUi
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.dispatchAction
import com.eried.eucplanet.data.model.withUnitsToggled
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.ui.navigation.Screen
import com.eried.eucplanet.diagnostics.ConnectionInfo
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.diagnostics.ServiceModeOverlay
import com.eried.eucplanet.diagnostics.ServiceOverlaySnapshot
import com.eried.eucplanet.diagnostics.ServiceOverlayState
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.ui.navigation.NavGraph
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Process-scoped: the theme customization widget is session-only, so we clear any
// persisted "on" state exactly once per launch (this survives Activity recreation
// such as a rotation, so toggling it on mid-session isn't undone).
private var widgetSessionReset = false
private var languageReconciled = false

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var themeController: com.eried.eucplanet.ui.theme.ThemeController
    @Inject lateinit var flicManager: FlicManager
    @Inject lateinit var wearBridge: com.eried.eucplanet.wear.WearBridge
    @Inject lateinit var garminBridge: com.eried.eucplanet.garmin.GarminBridge
    @Inject lateinit var tripRepository: com.eried.eucplanet.data.repository.TripRepository
    @Inject lateinit var wheelRepository: com.eried.eucplanet.data.repository.WheelRepository
    @Inject lateinit var incomingShareRepository:
        com.eried.eucplanet.data.repository.IncomingShareRepository
    @Inject lateinit var dropboxRepository:
        com.eried.eucplanet.data.repository.DropboxRepository
    @Inject lateinit var appHealthRepository:
        com.eried.eucplanet.data.repository.AppHealthRepository
    @Inject lateinit var appNotifier: com.eried.eucplanet.util.AppNotifier

    private val _settings = MutableStateFlow<AppSettings?>(null)

    private var volumeUpDownTime = 0L
    private var volumeDownDownTime = 0L
    private var volumeUpHoldFired = false
    private var volumeDownHoldFired = false
    private val holdThresholdMs = 600L

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Now that the rider has finished the permission flow, kick off the
        // global location stream so the Dashboard, Navigator and Studio all
        // see a fix the moment they're opened instead of each having to
        // start their own stream. startLocationUpdates() also seeds the
        // StateFlow from the OS's cached last-known fix, so the UI shows
        // a position effectively instantly when Maps / a weather widget /
        // anyone else recently used GPS.
        if (hasLocationPermission()) {
            tripRepository.startLocationUpdates()
        }
        val s = _settings.value
        val needsServiceForBackgroundFeature = s != null && canStartWheelService() && (
            // Voice loop needs the service alive between rides so the periodic
            // announcement keeps firing even before a wheel is connected — but
            // only in "ALWAYS" mode; the connected/riding modes have nothing to
            // say until a wheel is on the line.
            (s.voiceEnabled && s.voiceAnnounceWhen == "ALWAYS") ||
            // HUD companion: the embedded HTTP/SSE server lives inside
            // WheelService, so we need the service running for the HUD to
            // pair even before a wheel is on the line.
            s.hudServerEnabled
        )
        if (needsServiceForBackgroundFeature) {
            startForegroundService(Intent(this, WheelService::class.java))
        }
        // Whatever the rider answered (yes or no), refresh the warning list so
        // the dashboard top-bar indicator reflects the new permission state.
        appHealthRepository.refreshPermissionWarnings()
    }

    /** True if either fine or coarse location is granted. */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun canStartWheelService(): Boolean {
        val hasBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return hasBt || hasLoc
    }

    /**
     * Pinging the wear bridge on every resume rather than only at process
     * start makes the auto-launch reliable: opening the phone app brings
     * the watch app to the foreground each time, even if the watch service
     * was killed for memory after the previous session.
     */
    override fun onResume() {
        super.onResume()
        wearBridge.pingWatchToWake()
        garminBridge.pingWatchToWake()
        // Catch permission flips done in Settings while the app was in the
        // background — the warning indicator auto-clears when the rider
        // returns having granted what was missing.
        appHealthRepository.refreshPermissionWarnings()
    }

    /**
     * Guards [requestMissingPermissions] so we only ever fire it once per
     * MainActivity instance, even though the LaunchedEffect that drives it
     * may recompose. False until the first prompt has been shown.
     */
    private var permissionsAsked = false

    /**
     * Reads an Android Share / VIEW intent for an address or geo: URI,
     * parses it via [IncomingShareRepository.parse], and stashes the
     * result for the Navigator to pick up. Returns true if anything
     * usable was found, so onCreate / onNewIntent can decide whether to
     * route to the Builder.
     */
    private fun consumeShareIntent(intent: Intent?): Boolean {
        intent ?: return false
        // Dropbox OAuth (PKCE) bounces back as `db-<APPKEY>://1/connect?
        // code=...`. Intercept those before the generic share handler so
        // the code never gets fed to the geocoder.
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null &&
            data.scheme == "db-${com.eried.eucplanet.data.repository.DropboxRepository.APP_KEY}"
        ) {
            // Exchange the code for a token, then tell the rider whether the
            // link actually took. Without this the OAuth result is silent, so a
            // failed token exchange (e.g. no internet) looks identical to a
            // success and the rider is left wondering why Dropbox features stay
            // greyed out.
            lifecycleScope.launch {
                val ok = dropboxRepository.handleAuthCallback(data)
                appNotifier.post(
                    getString(if (ok) R.string.dropbox_link_ok else R.string.dropbox_link_failed)
                )
            }
            return true
        }
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        }?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val parsed = com.eried.eucplanet.data.repository
            .IncomingShareRepository.parse(raw) ?: return false
        incomingShareRepository.offer(parsed)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The launchMode is "standard"; a second SEND will start a new
        // task. This onNewIntent handles the case where Android delivers
        // the intent to an already-running instance instead (singleTop
        // semantics, which we get when the rider just shared again).
        consumeShareIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeShareIntent(intent)
        // requestMissingPermissions() is intentionally NOT called here.
        // On a clean install, asking before setContent runs means the runtime
        // permission dialogs come up over a black activity, the rider thinks
        // the app crashed, and the system has been seen to drop the empty-
        // looking activity while they are still reading the rationale. The
        // LaunchedEffect inside setContent below fires once the first frame is
        // composed, so the dialog always lands on top of a real UI.

        lifecycleScope.launch {
            settingsRepository.settings.collect {
                val first = _settings.value == null
                // Session-only theme widget: on the first load of a fresh process,
                // clear any persisted "on" (and mask this emission so it never
                // flashes on). In-session toggles after this pass through normally.
                val effective = if (!widgetSessionReset) {
                    widgetSessionReset = true
                    if (it.themeEditorEnabled) themeController.setEditorEnabled(false)
                    it.copy(themeEditorEnabled = false)
                } else it
                _settings.value = effective
                // Honour the "keep screen on" toggle. Setting the window flag
                // is idempotent so we don't need a delta check.
                if (it.phoneKeepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Detect a default app language as soon as we see a blank
                // `settings.language`, not only on the very first settings
                // emission. The synchronous seed above sets _settings.value
                // before this collect runs, so `first` is already false here
                // and the original gate never fired -- on a clean install the
                // app would end up with language="" and an empty picker. The
                // process-level languageReconciled flag stops this re-firing
                // every time settings update.
                if (it.language.isBlank() && !languageReconciled) {
                    languageReconciled = true
                    val detected = com.eried.eucplanet.util.LocaleHelper.detectSystemLanguage()
                    // Defensive: detectSystemLanguage already falls back to
                    // "en" for unrecognised system locales, but guard against
                    // a future regression that returns blank.
                    val safe = detected.ifBlank { "en" }
                    settingsRepository.update(it.copy(language = safe))
                }
                if (first) {
                    // Prime the location stream on cold launches where the
                    // permission was already granted on a previous run -- on
                    // a freshly-granted launch the permissionLauncher
                    // callback above handles this, but on subsequent launches
                    // no callback fires and the Dashboard would otherwise sit
                    // without a fix until the rider opens Navigator / Studio.
                    if (hasLocationPermission()) {
                        tripRepository.startLocationUpdates()
                    }
                    // Settings.language and the OS-level per-app locale can
                    // drift (e.g. user changes language via Android System
                    // Settings -> Languages, bypassing our in-app picker).
                    // Reconcile to whatever AppCompatDelegate actually has
                    // applied so the in-app language picker shows the truth,
                    // not a stale column. The blank-language path is handled
                    // separately above so on a clean install the picker is
                    // populated regardless of whether `first` ever fires.
                    if (it.language.isNotBlank()) {
                        val applied = com.eried.eucplanet.util.LocaleHelper.current()
                        if (applied.isNotBlank()) {
                            val normalized = com.eried.eucplanet.util.LocaleHelper.normalizeToSupportedTag(applied)
                            if (normalized != it.language) {
                                settingsRepository.update(it.copy(language = normalized))
                            }
                        }
                    }
                    // Gated on permission because Android 14+ crashes startForeground
                    // with location/connectedDevice types if neither perm is granted.
                    // Either always-on voice OR the HUD companion server can
                    // require the foreground service before a wheel is paired.
                    // The HUD-force debug prop is honoured too so emulator
                    // testers don't have to find the Compose toggle by tap.
                    val forceHud = com.eried.eucplanet.hud.protocol.HudDebug
                        .read("debug.eucplanet.hud.force") == "true"
                    val needsService = canStartWheelService() && (
                        (it.voiceEnabled && it.voiceAnnounceWhen == "ALWAYS") ||
                        it.hudServerEnabled ||
                        forceHud
                    )
                    if (needsService) {
                        startForegroundService(Intent(this@MainActivity, WheelService::class.java))
                    }
                }
            }
        }

        // Seed the saved settings synchronously so the FIRST composed frame
        // already carries the rider's custom theme. Without this, _settings is
        // null for one frame and the app flashes the system-default theme before
        // DataStore loads. Best-effort + time-boxed: on failure _settings stays
        // null and the theme falls back to the resolved/seeded built-in.
        if (_settings.value == null) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(700) { settingsRepository.get() }
                }
            }.getOrNull()?.let {
                _settings.value = it.copy(themeEditorEnabled = false)
                // The active theme now lives in ThemeController (resolved async),
                // so seed it synchronously from the just-loaded name too -- else a
                // process-death resume flashes pure black before the async resolve
                // lands. Built-ins seed instantly; saved themes finish async.
                themeController.seedSync(it.activeThemeName)
            }
        }

        setContent {
            val s by _settings.collectAsState()
            // Ask for the BLE / location / notification permissions on the
            // first composition. Doing it from Compose (instead of onCreate)
            // means the activity is fully drawn before the system dialog
            // appears, see the comment on permissionsAsked.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (!permissionsAsked) {
                    permissionsAsked = true
                    requestMissingPermissions()
                }
            }
            // The active theme's resolved colors come from ThemeController (held in
            // memory, re-derived from the persisted theme NAME on launch). The live
            // editor preview overrides them so the whole app re-skins instantly
            // while a slider drags; the target tool's pulse wins over both.
            androidx.compose.runtime.LaunchedEffect(Unit) { themeController.ensureResolved() }
            val resolvedColors = themeController.activeColors.collectAsState().value
            val liveColors = themeController.live.collectAsState().value
            val pulseColors = themeController.pulse.collectAsState().value
            val themeColors = pulseColors ?: liveColors ?: resolvedColors
            EucPlanetTheme(colors = themeColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    // Main-thread scope for the service overlay's settings/wheel
                    // action hooks (toggle units, mute, reset trip) — nav actions
                    // run synchronously on the click thread and don't need it.
                    val overlayScope = androidx.compose.runtime.rememberCoroutineScope()
                    // When a Share-to-app intent dropped a pending stop into
                    // the repository (see consumeShareIntent), jump straight
                    // to the route builder. The Builder's own LaunchedEffect
                    // then consumes the request and either drops the pin or
                    // surfaces a snackbar.
                    val pendingShare by incomingShareRepository.pending
                        .collectAsState()
                    androidx.compose.runtime.LaunchedEffect(pendingShare) {
                        if (pendingShare != null) {
                            runCatching {
                                navController.navigate("route_builder") {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                    // The navigation popup floats above the whole nav graph so
                    // turn cues stay visible on any screen while guiding,
                    // EXCEPT on the navigator map itself -- the map already
                    // shows the cue (and the rider doesn't want a popup
                    // covering the very thing they came here to see). The
                    // watch keeps receiving cues regardless of the phone
                    // overlay being suppressed, so the wrist still vibrates
                    // / updates when the rider's on the map screen.
                    val currentRoute by navController.currentBackStackEntryAsState()
                    val onMapScreen = currentRoute?.destination?.route == "route_builder"
                    // Per-screen rotation. The manifest allows rotation (fullUser);
                    // here we lock/unlock the activity per screen from settings.
                    // The main dashboard is portrait-locked by default; the
                    // navigator and other screens default to allowing rotation.
                    val routeNow = currentRoute?.destination?.route
                    androidx.compose.runtime.LaunchedEffect(
                        routeNow, s?.rotateDashboard, s?.rotateNavigator,
                        s?.rotateOtherScreens, s?.blockUpsideDown
                    ) {
                        val allow = when (routeNow) {
                            Screen.Dashboard.route, null -> s?.rotateDashboard ?: false
                            Screen.RouteBuilder.route -> s?.rotateNavigator ?: true
                            // The Studio is a fixed-portrait surface by design:
                            // its round buttons rotate their icons in place,
                            // camera-app style, and reflowing the layout breaks
                            // the recording canvas. Never rotate it.
                            Screen.OverlayStudio.route -> false
                            else -> s?.rotateOtherScreens ?: true
                        }
                        this@MainActivity.requestedOrientation = when {
                            // USER (vs FULL_USER) excludes reverse portrait on
                            // phones, the app-wide upside-down lockout.
                            allow && s?.blockUpsideDown == true ->
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                            allow -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavGraph(navController = navController)
                        com.eried.eucplanet.ui.navigator.NavigationOverlay(
                            onOpenMap = {
                                runCatching {
                                    navController.navigate("route_builder") {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            suppressOnPhone = onMapScreen
                        )
                        // Service-mode debug overlay — opens on volume key
                        // when DiagnosticsLogger is enabled. Sits at the
                        // top of the activity composition so it floats
                        // above every screen (dashboard, settings, etc).
                        val serviceOpen by ServiceOverlayState.open.collectAsState()
                        val serviceSnapshot by ServiceOverlayState.snapshot.collectAsState()
                        val activeSnapshot = serviceSnapshot
                        if (serviceOpen && activeSnapshot != null) {
                            ServiceModeOverlay(
                                snapshot = activeSnapshot,
                                onFireAction = { key ->
                                    // Same unified dispatch the dashboard tiles use:
                                    // dashboard-only actions run through this overlay's
                                    // ActionUi (it has the navController), everything
                                    // else falls through to the physical-surface path.
                                    dispatchAction(
                                        key,
                                        ui = object : ActionUi {
                                            override fun openNavigation() {
                                                navController.navigate(Screen.RouteBuilder.route) { launchSingleTop = true }
                                                ServiceOverlayState.dismiss()
                                            }
                                            override fun openStudio() {
                                                navController.navigate(Screen.OverlayStudio.createRoute(null)) { launchSingleTop = true }
                                                ServiceOverlayState.dismiss()
                                            }
                                            // OPEN_ABOUT / OPEN_SERVICE are dashboard-local
                                            // dialogs, so post the request to the dashboard
                                            // via the bus, then navigate there to open it.
                                            override fun openAbout() {
                                                com.eried.eucplanet.ui.dashboard.DashboardDialogBus.open("about")
                                                navController.navigate(Screen.Dashboard.route) { launchSingleTop = true }
                                                ServiceOverlayState.dismiss()
                                            }
                                            override fun openService() {
                                                com.eried.eucplanet.ui.dashboard.DashboardDialogBus.open("service")
                                                navController.navigate(Screen.Dashboard.route) { launchSingleTop = true }
                                                ServiceOverlayState.dismiss()
                                            }
                                            override fun openTrips() {
                                                navController.navigate(Screen.Recording.route) { launchSingleTop = true }
                                                ServiceOverlayState.dismiss()
                                            }
                                            override fun toggleUnits() {
                                                overlayScope.launch {
                                                    settingsRepository.update(settingsRepository.get().withUnitsToggled())
                                                }
                                            }
                                            override fun toggleAlarmsMuted() {
                                                overlayScope.launch {
                                                    val c = settingsRepository.get()
                                                    settingsRepository.update(c.copy(alarmsMuted = !c.alarmsMuted))
                                                }
                                            }
                                            override fun resetTrip() {
                                                overlayScope.launch {
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        wheelRepository.resetTripMeter()
                                                    }
                                                }
                                            }
                                        },
                                        fallback = { flicManager.dispatchActionByName(it) }
                                    )
                                    // Re-snapshot so the action-status readout reflects
                                    // any toggle that just flipped. The dispatch path is
                                    // async, so the rider can press Fire again to re-read.
                                    ServiceOverlayState.refresh(buildServiceOverlaySnapshot())
                                },
                                onRefresh = { ServiceOverlayState.refresh(buildServiceOverlaySnapshot()) },
                                onDismiss = { ServiceOverlayState.dismiss() }
                            )
                        }
                        // Floating theme editor — only mounted (and so only
                        // costing anything) when the rider enables it in
                        // Settings -> Display -> Theme editor.
                        if (s?.themeEditorEnabled == true) {
                            com.eried.eucplanet.ui.theme.ThemeEditorWidget()
                        }
                        // Radar mini lane lives inside DashboardScreen now,
                        // mounted directly in the dial Box. Keeping the radar
                        // visible only while the rider is on the dashboard
                        // matches where their eyes already are and avoids
                        // fighting other screens (navigator, studio, settings)
                        // for the screen-edge gutter.

                        // Root-level snackbar host for app-global transient
                        // messages posted from background code (services,
                        // repositories, the OAuth intent handler) via
                        // AppNotifier. Floats above every screen so these
                        // show as Material 3 snackbars instead of Toasts,
                        // no matter which screen is on top. Per-screen
                        // snackbars (LocalSnackbar) are unaffected.
                        val rootSnackbar = androidx.compose.runtime.remember {
                            androidx.compose.material3.SnackbarHostState()
                        }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            appNotifier.messages.collect { msg ->
                                rootSnackbar.showSnackbar(msg)
                            }
                        }
                        androidx.compose.material3.SnackbarHost(
                            hostState = rootSnackbar,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Service-mode override: while diagnostics is enabled, volume-UP
        // opens the debug overlay instead of firing its bound action.
        // Volume-DOWN deliberately falls through to the normal volume-key
        // handling below, so the rider can still bind an action to it (or
        // just change phone volume) while the overlay is up. The overlay
        // snapshots wheel state + history so the rider can inspect raw
        // values and fire any catalog action without needing a Flic button.
        // Service mode is a developer-only state behind the "Enter"
        // confirmation in the Service Mode dialog.
        if (DiagnosticsLogger.enabled.value &&
            keyCode == KeyEvent.KEYCODE_VOLUME_UP &&
            event?.repeatCount == 0
        ) {
            ServiceOverlayState.show(buildServiceOverlaySnapshot())
            return true
        }
        val s = _settings.value
        if (s != null && s.volumeKeysEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val now = System.currentTimeMillis()
            if (event?.repeatCount == 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> { volumeUpDownTime = now; volumeUpHoldFired = false }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { volumeDownDownTime = now; volumeDownHoldFired = false }
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP ->
                        if (!volumeUpHoldFired && now - volumeUpDownTime >= holdThresholdMs) {
                            volumeUpHoldFired = true
                            flicManager.dispatchActionByName(s.volumeUpHold)
                        }
                    KeyEvent.KEYCODE_VOLUME_DOWN ->
                        if (!volumeDownHoldFired && now - volumeDownDownTime >= holdThresholdMs) {
                            volumeDownHoldFired = true
                            flicManager.dispatchActionByName(s.volumeDownHold)
                        }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Service mode swallows the volume-UP key-up too, otherwise opening
        // the overlay on KeyDown would still fire the bound click-action on
        // KeyUp and the rider would get both the dialog and a HORN beep.
        // Volume-DOWN is left alone so its normal binding still works.
        if (DiagnosticsLogger.enabled.value &&
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        ) {
            return true
        }
        val s = _settings.value
        if (s != null && s.volumeKeysEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP ->
                    if (!volumeUpHoldFired) flicManager.dispatchActionByName(s.volumeUpClick)
                KeyEvent.KEYCODE_VOLUME_DOWN ->
                    if (!volumeDownHoldFired) flicManager.dispatchActionByName(s.volumeDownClick)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Builds a fresh snapshot for the service-mode debug overlay. Called when the overlay opens and after every Fire so the action-status readout updates. */
    private fun buildServiceOverlaySnapshot(): ServiceOverlaySnapshot {
        val s = _settings.value
        return ServiceOverlaySnapshot(
            wheel = wheelRepository.wheelData.value,
            history = wheelRepository.fullHistory.value,
            tripRecording = tripRepository.recording.value,
            imperialUnits = s?.imperialUnits ?: false,
            safetyActive = wheelRepository.safetySpeedActive.value,
            alarmsMuted = s?.alarmsMuted ?: false,
            connections = buildServiceConnections(s)
        )
    }

    /** Snapshot of every transport for the overlay's Connections tab, read
     *  from the repositories / bridges this activity already injects. */
    private fun buildServiceConnections(s: AppSettings?): List<ConnectionInfo> = buildList {
        add(
            ConnectionInfo(
                label = "Wheel (BLE)",
                state = wheelRepository.connectionState.value.name,
                detail = buildString {
                    append("device: ").append(wheelRepository.connectedDeviceName.value ?: "—").append('\n')
                    append("family: ").append(wheelRepository.connectedFamilyId ?: "—")
                    append("\n\n── all wheel data ──\n")
                    append(com.eried.eucplanet.diagnostics.reflectFields(wheelRepository.wheelData.value))
                }
            )
        )
        val nodes = wearBridge.pairedNodes.value
        add(
            ConnectionInfo(
                label = "Watch (Wear)",
                state = if (nodes.isEmpty()) "none" else "${nodes.size} node(s)",
                detail = if (nodes.isEmpty()) "no paired watch" else nodes.joinToString("\n")
            )
        )
        val flics = flicManager.pairedButtons.value
        add(
            ConnectionInfo(
                label = "Flic buttons",
                state = if (flics.isEmpty()) "none" else "${flics.size} paired",
                detail = if (flics.isEmpty()) "no buttons paired"
                else flics.joinToString("\n\n") { b ->
                    buildString {
                        append("name:  ").append(b.name ?: "?").append('\n')
                        append("addr:  ").append(b.bdAddr).append('\n')
                        // Flic2Button.connectionState: 0=disconnected 1=connecting
                        // 2=starting 3=ready.
                        append("state: ").append(b.connectionState)
                    }
                }
            )
        )
        add(
            ConnectionInfo(
                label = "HUD",
                state = if (s?.hudServerEnabled == true) "enabled" else "off",
                // No live HUD counters are exposed to the activity, so this is the
                // HUD config we have (endpoint + which screens / map style).
                detail = buildString {
                    append("endpoint: ")
                    append(s?.hudIp?.ifBlank { "mDNS auto" } ?: "mDNS auto")
                    append(':').append(s?.hudServerPort ?: 28080).append('\n')
                    append("screens:  ").append(s?.hudScreensEnabled?.ifBlank { "(default)" } ?: "(default)").append('\n')
                    append("map:      ").append(s?.hudMapStyle?.ifBlank { "(default)" } ?: "(default)")
                }
            )
        )
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
