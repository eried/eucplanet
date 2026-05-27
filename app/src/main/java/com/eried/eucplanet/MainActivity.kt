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
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
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

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var flicManager: FlicManager
    @Inject lateinit var wearBridge: com.eried.eucplanet.wear.WearBridge
    @Inject lateinit var tripRepository: com.eried.eucplanet.data.repository.TripRepository
    @Inject lateinit var incomingShareRepository:
        com.eried.eucplanet.data.repository.IncomingShareRepository

    private val settingsFlow: StateFlow<AppSettings?> get() = _settings.asStateFlow()
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
        if (s != null && s.voiceEnabled && !s.voiceOnlyWhenConnected && canStartWheelService()) {
            startForegroundService(Intent(this, WheelService::class.java))
        }
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
                _settings.value = it
                // Honour the "keep screen on" toggle. Setting the window flag
                // is idempotent so we don't need a delta check.
                if (it.phoneKeepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    if (it.language.isBlank()) {
                        // First launch ever: persist the detected supported
                        // locale so the in-app picker has the right initial
                        // value, but DO NOT call LocaleHelper.apply().
                        // setApplicationLocales triggers an Activity.recreate
                        // on every running activity, and on a clean install
                        // this fires while the runtime permission dialogs are
                        // open, the recreate races with the dialog and the
                        // rebuilt activity ends up behind the launcher,
                        // leaving the rider answering permissions over the
                        // home screen. Skipping the apply is safe: with no
                        // AppCompatDelegate override, Android falls back to
                        // the system locale (which is exactly what detect
                        // mapped from), so the UI ends up in the same
                        // language anyway. The user's later pick in Settings
                        // calls LocaleHelper.apply directly when they ARE
                        // overriding the system.
                        val detected = com.eried.eucplanet.util.LocaleHelper.detectSystemLanguage()
                        settingsRepository.update(it.copy(language = detected))
                    } else {
                        // Settings.language and the OS-level per-app locale can
                        // drift (e.g. user changes language via Android System
                        // Settings → Languages, bypassing our in-app picker).
                        // Reconcile to whatever AppCompatDelegate actually has
                        // applied so the in-app language picker shows the
                        // truth, not a stale column.
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
                    if (it.voiceEnabled && !it.voiceOnlyWhenConnected && canStartWheelService()) {
                        startForegroundService(Intent(this@MainActivity, WheelService::class.java))
                    }
                }
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
            EucPlanetTheme(
                themeMode = s?.themeMode ?: "black",
                accentColor = s?.accentColor ?: "blue"
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
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
                        // Radar threat lane bar. Sits on a screen edge so it
                        // doesn't fight the navigation popup (centred) for
                        // visual space. Its view model gates visibility off
                        // pairing + connection state, no extra suppression
                        // needed per screen.
                        com.eried.eucplanet.ui.radar.RadarOverlay()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
