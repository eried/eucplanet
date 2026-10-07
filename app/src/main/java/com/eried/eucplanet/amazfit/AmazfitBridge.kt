package com.eried.eucplanet.amazfit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.nav.NavigationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion to the Zepp OS watch app in `amazfit-watch-app/`.
 *
 * Third sibling of [com.eried.eucplanet.wear.WearBridge] and
 * `GarminBridge`: same settings, same snapshot vocabulary ([AmazfitKeys]),
 * same farewell-on-stop and quit semantics. The transport is inverted:
 * Zepp OS gives a third-party phone app no way to push to the watch, so the
 * watch's Side Service (JavaScript inside the Zepp phone app) polls
 * `http://127.0.0.1:AMAZFIT_PORT/state` and this bridge answers. Everything
 * that is a push on the other two surfaces is a queued event here, delivered
 * with the next poll ([AmazfitInbox]).
 *
 * Inject-and-forget: `EucPlanetApp.onCreate()` calls [start] once and the
 * bridge lives for the whole process. With no watch polling it costs a
 * listening loopback socket and a once-a-second bookkeeping tick.
 */
@Singleton
class AmazfitBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val externalGpsRepository: com.eried.eucplanet.data.repository.ExternalGpsRepository,
    private val tripRepository: com.eried.eucplanet.data.repository.TripRepository,
    private val navigationEngine: NavigationEngine,
    private val flicManager: FlicManager,
    private val themeController: com.eried.eucplanet.ui.theme.ThemeController,
    private val inbox: AmazfitInbox
) {
    companion object {
        private const val TAG = "AmazfitBridge"
        /** A watch stays in the paired list this long after its last poll:
         *  long enough to ride out the Zepp app being backgrounded for a bit,
         *  short enough that a closed watch app drops off the Settings card. */
        private const val PRESENCE_WINDOW_MS = 15_000L
        /** How long the QUIT path waits for the watch to fetch the event before
         *  giving up; the process is usually about to die right after. */
        private const val QUIT_WAIT_MS = 1_500L
        private const val BIND_RETRY_MS = 30_000L
        private const val DEFAULT_NAME = "Amazfit watch"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    /** Set by [publishFarewell]: `/state` serves a disconnected frame until the
     *  wheel is connected again, so a watch that polls during tear-down sees
     *  the dial zero out instead of the last live frame. */
    @Volatile private var farewell = false
    private var server: AmazfitLocalServer? = null

    /** Name of the watch currently polling, or empty when none has polled in
     *  the last [PRESENCE_WINDOW_MS]. Feeds the Settings "Devices" card. */
    private val _pairedDevices = MutableStateFlow<List<String>>(emptyList())
    val pairedDevices: StateFlow<List<String>> = _pairedDevices

    /** Polls per second, exponentially smoothed (alpha 0.25) like the Garmin
     *  badge, so a 1 Hz poll reads as a steady 1.0 rather than 0/1/0/1. */
    private val _deliveryRateHz = MutableStateFlow(0.0)
    val deliveryRateHz: StateFlow<Double> = _deliveryRateHz

    /** Epoch millis of the last `/state` poll. The Settings card reads this
     *  through the same 3 s window it uses for Garmin's Live badge. */
    private val _lastSuccessAtMs = MutableStateFlow(0L)
    val lastSuccessAtMs: StateFlow<Long> = _lastSuccessAtMs

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Amazfit bridge starting (loopback port $AMAZFIT_PORT)")

        // Bind with retry: another app squatting the port must not crash us,
        // and the rider may free it later.
        scope.launch {
            while (true) {
                val s = AmazfitLocalServer(AMAZFIT_PORT, ::handle) { msg -> Log.w(TAG, msg) }
                if (s.start()) {
                    server = s
                    Log.i(TAG, "listening on 127.0.0.1:${s.boundPort}")
                    return@launch
                }
                Log.w(TAG, "port $AMAZFIT_PORT busy, retrying in ${BIND_RETRY_MS / 1000} s")
                delay(BIND_RETRY_MS)
            }
        }

        // Presence + rate bookkeeping, once a second.
        scope.launch {
            val alpha = 0.25
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val instant = inbox.takePollCount().toDouble()
                _deliveryRateHz.value = alpha * instant + (1 - alpha) * _deliveryRateHz.value
                val names = if (inbox.hasPolledWithin(PRESENCE_WINDOW_MS, now)) {
                    listOf(inbox.watchName.ifBlank { DEFAULT_NAME })
                } else {
                    emptyList()
                }
                if (names != _pairedDevices.value) _pairedDevices.value = names
                if (farewell && wheelRepository.connectionState.value == ConnectionState.CONNECTED) {
                    farewell = false
                }
            }
        }
    }

    /** Zepp OS has no remote launch for third-party mini programs, so there is
     *  nothing to wake. Kept so callers can treat the three bridges alike. */
    fun pingWatchToWake() = Unit

    /**
     * Mirrors the other bridges' farewell: from now until the wheel is back,
     * `/state` answers with a disconnected, zeroed frame. The watch's own 10 s
     * stale timer still covers a phone that dies before it can answer.
     */
    fun publishFarewell() {
        if (!started) return
        farewell = true
    }

    /**
     * Queues a QUIT and waits up to [QUIT_WAIT_MS] for the watch to fetch it.
     * Returns immediately when no watch has polled recently, so the callers'
     * shutdown paths never stall on an absent watch. Blocking on purpose, like
     * the Garmin variant: `stopEverything()` kills the process right after.
     */
    fun sendCloseToWatchBlocking() {
        try {
            val now = System.currentTimeMillis()
            if (!inbox.hasPolledWithin(PRESENCE_WINDOW_MS, now)) return
            inbox.enqueue(mapOf(AmazfitKeys.KIND to AmazfitKeys.KIND_QUIT))
            val fetched = inbox.awaitDrained(QUIT_WAIT_MS)
            Log.i(TAG, "Amazfit close ${if (fetched) "fetched by" else "left for"} the watch")
        } catch (e: Exception) {
            Log.d(TAG, "Amazfit close skipped: ${e.message}")
        }
    }

    // --- HTTP ---------------------------------------------------------------

    private fun handle(req: AmazfitLocalServer.Request): AmazfitLocalServer.Response = when {
        req.method == "GET" && req.path == AMAZFIT_PATH_STATE -> {
            val now = System.currentTimeMillis()
            inbox.notePoll(now)
            _lastSuccessAtMs.value = now
            AmazfitLocalServer.Response(200, AmazfitJson.encode(buildSnapshot(now)))
        }
        req.method == "POST" && req.path == AMAZFIT_PATH_CONTROL -> {
            val cmd = AmazfitJson.cmdOf(req.body)
            if (cmd == null) {
                AmazfitLocalServer.Response(400, "{\"ok\":false}")
            } else {
                handleControl(cmd)
                AmazfitLocalServer.Response(200, "{\"ok\":true}")
            }
        }
        else -> AmazfitLocalServer.Response(404, "{\"error\":\"not found\"}")
    }

    private fun buildSnapshot(nowMs: Long): Map<String, Any> {
        val s: AppSettings = runBlocking { settingsRepository.get() }
        // Same gauge-max rule as the dashboard and the other two bridges.
        val effTilt = if (wheelRepository.safetySpeedActive.value) s.safetyTiltbackKmh else s.tiltbackSpeedKmh
        val gaugeMax = (((effTilt / 10f).toInt() + 1) * 10f).coerceAtLeast(30f)
        val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
        val goodbye = farewell && !connected
        val data = if (goodbye) WheelData() else wheelRepository.wheelData.value
        val nav = navigationEngine.navState.value
        val navMirror = AmazfitSnapshot.Nav(
            show = nav.active && !nav.minimized && nav.cueVisible && s.watchShowNavigation,
            angleDeg = nav.arrowAngleDeg(),
            primary = nav.primaryText,
            distance = nav.distanceText,
            arrived = nav.arrived
        )
        return AmazfitSnapshot.encode(
            data = data,
            connected = connected && !goodbye,
            wheelName = wheelRepository.modelName.value,
            maxSpeedKmh = gaugeMax,
            settings = s,
            speedMultiplier = cheatState.speedDisplayMultiplier.value,
            phoneBatteryPercent = readPhoneBatteryPercent(),
            accentArgb = com.eried.eucplanet.ui.theme.ThemeAccent.primaryArgb(themeController.activeColors.value),
            gps = computeGpsExtraSpeed(s),
            nav = navMirror,
            events = inbox.drainEvents(),
            nowMs = nowMs,
            // Service Mode recording: the watch reports its inputs only then.
            diag = com.eried.eucplanet.diagnostics.DiagnosticsLogger.enabled.value
        )
    }

    /** Routes one control intent from the watch into the same code paths the
     *  Wear OS and Garmin bridges use. */
    private fun handleControl(cmd: String) {
        // Input reports are chatty while Service Mode records; keep logcat quiet.
        if (!cmd.startsWith(AmazfitControl.DEBUG_PREFIX)) Log.i(TAG, "control from Amazfit: $cmd")
        when {
            cmd == AmazfitControl.HORN -> wheelRepository.sendHorn()
            cmd == AmazfitControl.LIGHT_ON || cmd == AmazfitControl.LIGHT_OFF ->
                wheelRepository.toggleLight()
            cmd.startsWith(AmazfitControl.ACTION_PREFIX) ->
                flicManager.dispatchActionByName(cmd.removePrefix(AmazfitControl.ACTION_PREFIX))
            cmd.startsWith(AmazfitControl.DEBUG_PREFIX) -> {
                // Input-event report, only while Service Mode records.
                val ev = cmd.removePrefix(AmazfitControl.DEBUG_PREFIX)
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("amazfit input: $ev")
            }
            cmd.startsWith(AmazfitControl.WATCH_INFO_PREFIX) -> {
                val info = cmd.removePrefix(AmazfitControl.WATCH_INFO_PREFIX)
                Log.i(TAG, "Amazfit watch info: $info")
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.info("amazfit: $info")
                info.split('|')
                    .firstOrNull { it.startsWith("model=") }
                    ?.removePrefix("model=")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { inbox.watchName = it }
            }
            else -> Log.w(TAG, "unknown Amazfit control: $cmd")
        }
    }

    /** Mirror of `WearBridge.computeGpsExtraSpeed` / `GarminBridge`; keep the
     *  three in sync so every dial shows the dashboard's GPS extra speed. */
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
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }
}
