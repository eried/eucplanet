package com.eried.eucplanet.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion to the watch app.
 *
 * Subscribes to [WheelRepository] state and publishes a compact snapshot to the
 * Wearable Data Layer at `/euc/state`, throttled to 5 Hz so the watch face
 * stays smooth without burning Bluetooth bandwidth. The watch reads the same
 * [WatchKeys] this writer uses; keep them in sync if you add a field.
 *
 * Started from [com.eried.eucplanet.EucPlanetApplication] so it lives for the
 * whole app process. No Activity lifecycle required, since the watch app
 * deserves data even when the user is on the lock screen looking at their
 * Watch Ultra.
 *
 * Control messages flow the other direction through [PhoneWearListenerService]
 * which routes them back to the repository.
 */
@Singleton
class WearBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val externalGpsRepository: com.eried.eucplanet.data.repository.ExternalGpsRepository,
    private val tripRepository: com.eried.eucplanet.data.repository.TripRepository,
    private val navigationEngine: com.eried.eucplanet.nav.NavigationEngine,
    private val themeController: com.eried.eucplanet.ui.theme.ThemeController
) {
    companion object {
        private const val TAG = "WearBridge"
        // Throttle so the watch doesn't get hammered at the 250 ms BLE poll rate.
        // 200 ms = 5 Hz, smooth on the gauge, fits the ambient screen budget.
        private const val PUBLISH_INTERVAL_MS = 200L

        private const val PATH_STATE = "/euc/state"
        private const val K_CONNECTED = "c"
        private const val K_SPEED = "s"
        private const val K_BATTERY = "b"
        private const val K_PHONE_BATT = "b2"
        private const val K_VOLTAGE = "v"
        private const val K_CURRENT = "i"
        private const val K_PWM = "p"
        private const val K_TEMP = "t"
        private const val K_TRIP_KM = "tr"
        private const val K_TORQUE = "tq"
        private const val K_LIGHT_ON = "l"
        private const val K_MAX_SPEED = "ms"
        private const val K_WHEEL_NAME = "n"
        private const val K_HAS_HORN = "ch"
        private const val K_HAS_LIGHT = "cl"
        private const val K_IMPERIAL = "im"
        // Resolved per-unit string codes mirroring Units.effective*Unit on the
        // phone: speed "kmh"/"mph"/"ms"/"kn", distance "km"/"mi"/"m"/"ft"/"mil",
        // temperature "C"/"F"/"K". An older watch APK ignores these and falls
        // back to K_IMPERIAL; a newer watch prefers them.
        private const val K_UNIT_SPEED = "us"
        private const val K_UNIT_DISTANCE = "ud"
        private const val K_UNIT_TEMP = "ut"
        private const val K_ACCENT = "ac"
        // Packed custom-theme colors for the watch (background/gauge/battery/text).
        // Field order is fixed by ThemeAccent.packForWatch / WatchColors.
        private const val K_THEME = "thm"
        private const val K_TIMESTAMP = "ts"
        // GPS extra-speed readout, mirrors the phone dashboard's gpsExtraSpeed.
        // K_GPS_SPEED is Float.NaN when there is nothing to show; K_GPS_SOURCE
        // is "EXTERNAL" / "PHONE" / "" matching DashboardViewModel's sourceKey.
        private const val K_GPS_SPEED = "gs"
        private const val K_GPS_SOURCE = "gsr"
        // Watch-display option keys mirror WatchKeys.OPT_* on the wear side.
        private const val K_OPT_KEEP_ON = "wko"
        private const val K_OPT_SHOW_WHEEL_BATT = "wsb"
        private const val K_OPT_SHOW_PHONE_BATT = "wpb"
        private const val K_OPT_SHOW_WATCH_BATT = "wwb"
        private const val K_OPT_PWM_DISPLAY = "wpd"
        private const val K_OPT_SHOW_SPEED_UNIT = "wsu"
        private const val K_OPT_PRIORITIZE_PWM = "wpp"
        private const val K_OPT_DIAL_ROTATION = "wrot"
        private const val K_OPT_GAUGE_BAND = "wgb"
        private const val K_OPT_GAUGE_ORANGE = "wgo"
        private const val K_OPT_GAUGE_RED = "wgr"
        private const val K_STEM1_CLICK = "s1c"
        private const val K_STEM1_HOLD = "s1h"
        private const val K_STEM2_CLICK = "s2c"
        private const val K_STEM2_HOLD = "s2h"
        private const val K_SCREEN1_CLICK = "b1c"
        private const val K_SCREEN1_HOLD = "b1h"
        private const val K_SCREEN2_CLICK = "b2c"
        private const val K_SCREEN2_HOLD = "b2h"
        private const val K_HAPTIC_ON_ACTION = "hap"
        // Navigation mirror, only populated when the rider opted in via the
        // Watch settings. K_NAV_ACTIVE already folds in that toggle and the
        // phone popup's minimized state, so the watch just shows/hides on it.
        private const val K_NAV_ACTIVE = "na"
        private const val K_NAV_ANGLE = "ng"
        private const val K_NAV_PRIMARY = "np"
        private const val K_NAV_DISTANCE = "nd"
        private const val K_NAV_ARRIVED = "nar"

        private const val PATH_WAKE = "/euc/wake"
        private const val PATH_QUIT = "/euc/quit"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /**
     * Names of currently-paired Wear OS nodes — empty when no watch is
     * paired, otherwise one entry per Wear OS device the phone has
     * connected to (typically one, occasionally more if the rider has both
     * a Galaxy Watch and a Pixel Watch). Polled every 5 s on a background
     * coroutine so the Settings UI's collapsible "Paired devices" card has
     * something fresh to render. Empty list means "show no Wear OS row".
     */
    private val _pairedNodes = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val pairedNodes: kotlinx.coroutines.flow.StateFlow<List<String>> = _pairedNodes

    /**
     * Idempotent. Begins streaming snapshots to any paired Wear OS device.
     * Safe to call from Application.onCreate even if no watch is paired; the
     * Data Layer just keeps a local cache that future watches sync from.
     */
    /**
     * Sends a one-shot `/euc/wake` Message to every paired Wear OS node so
     * the watch's [com.eried.eucplanet.wear.bridge.WatchBridgeService] can
     * launch MainActivity. Gated by the user's `watchAutoStart` setting.
     * Best-effort: failures are logged at DEBUG and don't propagate. Called
     * once on bridge startup and again from MainActivity.onResume() so the
     * watch wakes whenever the user re-opens the phone app.
     */
    /**
     * Synchronous best-effort: tells the paired watch's bridge service to
     * close MainActivity. Sent right before WheelService tears its session
     * down, so the watch dial flips to "phone gone" instantly instead of
     * holding on a stale frame. Runs blocking on the caller because we
     * usually call this from a service onDestroy where the process is about
     * to exit; a fire-and-forget launch would be cancelled mid-flight.
     * Returns quickly if no phone-paired watch is connected.
     */
    fun sendCloseToWatchBlocking() {
        try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            if (nodes.isEmpty()) return
            val messageClient = Wearable.getMessageClient(context)
            nodes.forEach { node: Node ->
                try {
                    Tasks.await(messageClient.sendMessage(node.id, PATH_QUIT, ByteArray(0)))
                } catch (e: Exception) {
                    Log.d(TAG, "watch close to ${node.id} failed: ${e.message}")
                }
            }
            Log.i(TAG, "watch close sent to ${nodes.size} node(s)")
        } catch (e: Exception) {
            Log.d(TAG, "watch close skipped: ${e.message}")
        }
    }

    fun pingWatchToWake() {
        scope.launch {
            try {
                val settings = settingsRepository.get()
                if (!settings.watchAutoStart) return@launch
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                if (nodes.isEmpty()) {
                    Log.d(TAG, "watch wake: no paired nodes")
                    return@launch
                }
                nodes.forEach { node: Node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_WAKE, ByteArray(0))
                }
                Log.i(TAG, "watch wake sent to ${nodes.size} node(s)")
            } catch (e: Exception) {
                Log.d(TAG, "watch wake skipped: ${e.message}")
            }
        }
    }

    /**
     * Last-gasp publish when the phone-side service is being destroyed
     * gracefully (user kills the app, system stops the foreground service,
     * etc.). Sends one frame marked disconnected with zeroed telemetry so the
     * watch can flip to "--" instantly instead of waiting out its 3-second
     * stale timer. If the process is hard-killed and onDestroy never runs,
     * the watch's stale-detection takes over as a fallback.
     */
    fun publishFarewell() {
        if (!started) return
        try {
            val s = runBlocking { settingsRepository.get() }
            publish(
                data = com.eried.eucplanet.data.model.WheelData(),
                state = ConnectionState.DISCONNECTED,
                name = wheelRepository.modelName.value,
                maxSpeed = 30f,
                settings = s
            )
        } catch (e: Exception) {
            Log.d(TAG, "farewell publish skipped: ${e.message}")
        }
    }

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Wear bridge starting (publish=${PUBLISH_INTERVAL_MS} ms)")

        // First wake on bridge startup. Additional wakes fire whenever
        // MainActivity resumes; see pingWatchToWake() below.
        pingWatchToWake()

        // Background paired-node poller. Refreshes [pairedNodes] every 5 s
        // so the Settings UI can show which Wear OS watches are connected.
        // 5 s is rare enough not to drain battery and frequent enough to
        // catch a watch coming online while the user is on Settings.
        scope.launch {
            while (true) {
                try {
                    val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    val names = nodes.map { it.displayName }
                    if (names != _pairedNodes.value) {
                        _pairedNodes.value = names
                    }
                } catch (e: Exception) {
                    if (_pairedNodes.value.isNotEmpty()) _pairedNodes.value = emptyList()
                }
                delay(5_000L)
            }
        }

        // Periodic publisher rather than a Flow combine. Reasoning: when the
        // wheel is disconnected the upstream flows don't emit, so a sample +
        // distinctUntilChanged pipeline goes silent, and the watch ends up
        // in the "phone not here" placeholder even though the phone app is
        // running and paired. Polling at PUBLISH_INTERVAL_MS keeps the
        // watch's freshness signal alive without per-emission complexity.
        scope.launch {
            while (true) {
                try {
                    // Watch gauge max must match the phone dashboard gauge max so the
                    // two dials show the same range. Dashboard computes:
                    //   gaugeMax = ((effectiveTiltback / 10) + 1) * 10
                    // where effectiveTiltback is the safety-tiltback when legal mode is
                    // on, normal tiltback otherwise. Mirroring that here.
                    val s = settingsRepository.get()
                    val effTilt = if (wheelRepository.safetySpeedActive.value)
                        s.safetyTiltbackKmh else s.tiltbackSpeedKmh
                    // Mirror the phone dashboard's 30 km/h floor so the watch
                    // gauge stays usable even when the wheel reports no tilt.
                    val gaugeMax = (((effTilt / 10f).toInt() + 1) * 10f).coerceAtLeast(30f)
                    publish(
                        data = wheelRepository.wheelData.value,
                        state = wheelRepository.connectionState.value,
                        name = wheelRepository.modelName.value,
                        maxSpeed = gaugeMax,
                        settings = s
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "publish loop error", e)
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    private fun publish(
        data: com.eried.eucplanet.data.model.WheelData,
        state: ConnectionState,
        name: String?,
        maxSpeed: Float,
        settings: AppSettings
    ) {
        try {
            val request = PutDataMapRequest.create(PATH_STATE).apply {
                dataMap.putBoolean(K_CONNECTED, state == ConnectionState.CONNECTED)
                dataMap.putString(K_WHEEL_NAME, name ?: "")
                // Apply the Quake-console daredevilNN multiplier (1.0 when no cheat).
                // The watch only renders telemetry, doesn't record, so faking here is safe.
                dataMap.putFloat(K_SPEED, data.speed * cheatState.speedDisplayMultiplier.value)
                dataMap.putInt(K_BATTERY, data.batteryPercent)
                dataMap.putInt(K_PHONE_BATT, readPhoneBatteryPercent())
                dataMap.putFloat(K_VOLTAGE, data.voltage)
                dataMap.putFloat(K_CURRENT, data.current)
                dataMap.putFloat(K_PWM, data.pwm)
                dataMap.putFloat(K_TEMP, data.maxTemperature)
                dataMap.putFloat(K_TRIP_KM, data.tripDistance)
                dataMap.putFloat(K_TORQUE, data.torque)
                dataMap.putBoolean(K_LIGHT_ON, data.lightOn)
                dataMap.putFloat(K_MAX_SPEED, maxSpeed)
                // V14 family always has horn + light; if/when we add wheels that
                // don't, gate these on WheelAdapter.capabilities. For now true.
                dataMap.putBoolean(K_HAS_HORN, true)
                dataMap.putBoolean(K_HAS_LIGHT, true)
                // Resolved per-unit codes so the watch can honour knots / m/s /
                // feet / Kelvin etc. K_IMPERIAL is kept as a coarse fallback for
                // an out-of-date watch APK that predates these keys.
                dataMap.putString(K_UNIT_SPEED, com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings))
                dataMap.putString(K_UNIT_DISTANCE, com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings))
                dataMap.putString(K_UNIT_TEMP, com.eried.eucplanet.util.Units.effectiveTempUnit(settings))
                dataMap.putBoolean(K_IMPERIAL, com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings) == "mph")
                // The active theme's primary as "#AARRGGBB" so the watch follows
                // the custom theme; the watch parses hex (and still accepts legacy keys).
                dataMap.putString(
                    K_ACCENT,
                    com.eried.eucplanet.ui.theme.ThemeAccent.primaryArgb(
                        themeController.activeColors.value
                    )
                )
                // Full theme palette so the watch face background, gauge, battery
                // and text follow the rider's custom theme, not just the accent.
                dataMap.putString(
                    K_THEME,
                    com.eried.eucplanet.ui.theme.ThemeAccent.packForWatch(
                        themeController.activeColors.value
                    )
                )
                dataMap.putBoolean(K_OPT_KEEP_ON, settings.watchKeepScreenOn)
                dataMap.putBoolean(K_OPT_SHOW_WHEEL_BATT, settings.watchShowWheelBattery)
                dataMap.putBoolean(K_OPT_SHOW_PHONE_BATT, settings.watchShowPhoneBattery)
                dataMap.putBoolean(K_OPT_SHOW_WATCH_BATT, settings.watchShowWatchBattery)
                dataMap.putString(K_OPT_PWM_DISPLAY, settings.watchPwmDisplay)
                dataMap.putBoolean(K_OPT_SHOW_SPEED_UNIT, settings.watchShowSpeedUnit)
                dataMap.putBoolean(K_OPT_PRIORITIZE_PWM, settings.watchPrioritizePwm)
                dataMap.putInt(K_OPT_DIAL_ROTATION, settings.watchDialRotationDeg)
                dataMap.putBoolean(K_OPT_GAUGE_BAND, settings.showGaugeColorBand)
                dataMap.putInt(K_OPT_GAUGE_ORANGE, settings.gaugeOrangeThresholdPct)
                dataMap.putInt(K_OPT_GAUGE_RED, settings.gaugeRedThresholdPct)
                dataMap.putString(K_STEM1_CLICK, settings.watchStem1Click)
                dataMap.putString(K_STEM1_HOLD, settings.watchStem1Hold)
                dataMap.putString(K_STEM2_CLICK, settings.watchStem2Click)
                dataMap.putString(K_STEM2_HOLD, settings.watchStem2Hold)
                dataMap.putString(K_SCREEN1_CLICK, settings.watchScreen1Click)
                dataMap.putString(K_SCREEN1_HOLD, settings.watchScreen1Hold)
                dataMap.putString(K_SCREEN2_CLICK, settings.watchScreen2Click)
                dataMap.putString(K_SCREEN2_HOLD, settings.watchScreen2Hold)
                dataMap.putBoolean(K_HAPTIC_ON_ACTION, settings.watchHapticOnAction)
                // GPS extra speed: computed exactly like DashboardViewModel.
                // gpsExtraSpeed so the watch mirrors the phone dashboard.
                val gps = computeGpsExtraSpeed(settings)
                dataMap.putFloat(K_GPS_SPEED, gps?.first ?: Float.NaN)
                dataMap.putString(K_GPS_SOURCE, gps?.second ?: "")
                // Navigation popup mirror. navShow folds in the rider's watch
                // opt-in and the phone popup's minimized state; when it is off
                // K_NAV_ACTIVE is false and the watch hides the overlay (with
                // a fade). The content fields (primary, distance, arrived,
                // angle) are NOT zeroed alongside navShow -- the watch's
                // AnimatedVisibility re-reads them during the fade animation,
                // and clearing them mid-fade would swap the Flag icon back to
                // a Navigation arrow and blank the "You have arrived" text
                // while the popup is still on screen visibly fading out.
                val nav = navigationEngine.navState.value
                // cueVisible folds in the phone popup's transient timeout, so
                // the watch shows nav only while the phone's popup is on screen.
                val navShow = nav.active && !nav.minimized && nav.cueVisible &&
                    settings.watchShowNavigation
                dataMap.putBoolean(K_NAV_ACTIVE, navShow)
                dataMap.putFloat(K_NAV_ANGLE, nav.arrowAngleDeg())
                dataMap.putString(K_NAV_PRIMARY, nav.primaryText)
                dataMap.putString(K_NAV_DISTANCE, nav.distanceText)
                dataMap.putBoolean(K_NAV_ARRIVED, nav.arrived)
                // DataItems dedupe by content. Bumping a timestamp guarantees
                // the watch sees every snapshot when the values stop changing
                // (e.g. wheel idle, but we want the connection-state heartbeat).
                dataMap.putLong(K_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
        }
    }

    /**
     * Snapshot of the dashboard's GPS extra-speed indicator for the watch.
     *
     * This is a deliberate, line-for-line mirror of
     * [com.eried.eucplanet.ui.dashboard.DashboardViewModel.gpsExtraSpeed];
     * keep the two in sync if either changes. Returns a `Pair(speedKmh,
     * sourceKey)` where sourceKey is "EXTERNAL" or "PHONE", or null when the
     * dashboard would show nothing (feature off, hidden, or no fresh source).
     */
    private fun computeGpsExtraSpeed(settings: AppSettings): Pair<Float, String>? {
        if (!settings.gpsShowOnDashboard) return null
        val externalSample = externalGpsRepository.currentSample.value
        val location = tripRepository.currentLocation.value
        val externalFresh = externalSample != null &&
            System.currentTimeMillis() - externalSample.timestamp < 5_000L
        return when {
            settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            location != null && location.hasSpeed() ->
                (location.speed * 3.6f) to "PHONE"
            !settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            else -> null
        }
    }

    private fun readPhoneBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { lvl ->
            if (lvl in 0..100) return lvl
        }
        // Fallback to the sticky ACTION_BATTERY_CHANGED broadcast on devices where
        // BATTERY_PROPERTY_CAPACITY isn't reliable.
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }

}
