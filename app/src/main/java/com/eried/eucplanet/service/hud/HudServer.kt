package com.eried.eucplanet.service.hud

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.ui.theme.AccentOptions
import com.eried.eucplanet.ui.theme.AccentTeal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Phone-side dialer that streams the current wheel telemetry to an external
 * HUD over a WebSocket.
 *
 * Architecture note: in v0.1.4 we flipped roles. Earlier versions ran a Ktor
 * server here so the HUD could connect *in*; phone hotspots that filter
 * multicast or enforce client isolation made that path unreachable for many
 * testers. Now the HUD is the listener (`:hud` module's `HudServer.kt`) and
 * we dial *out* using the IP the rider reads off the HUD's banner. Outbound
 * TCP from a phone almost always works regardless of softAP policy.
 *
 * The class name stayed [HudServer] for storage-key compatibility (the
 * `hudServerEnabled` flag in DataStore predates the rename); the role is
 * now strictly client.
 *
 * Lifecycle is owned by [com.eried.eucplanet.service.WheelService] -- start()
 * fires when the rider toggles the link on AND service is up, stop() on
 * either flipping off.
 */
@Singleton
class HudServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val externalGpsRepository: ExternalGpsRepository,
    private val tripRepository: TripRepository,
    private val navigationEngine: NavigationEngine,
    private val commandSink: HudCommandSink
) {

    companion object {
        private const val TAG = "HudServer"
        // 5 Hz: same rate WearBridge uses. Smooth speed needle without
        // saturating the hotspot.
        private const val PUBLISH_INTERVAL_MS = 200L
        // Reconnect backoff: 1s, 2s, 4s, capped at 5s. Below the cap the
        // rider gets a fresh try every time they walk back into range;
        // above it the HUD feels stuck.
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 5_000L
        // mDNS resolve timeout. Generous so a flaky multicast path on the
        // hotspot gets a fair chance, but bounded so the rider sees the
        // "no IP / not autodetected" state within a reasonable wait.
        private const val MDNS_RESOLVE_TIMEOUT_MS = 5_000L
        private const val MULTICAST_LOCK_TAG = "eucplanet-hud-discovery"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Several HudState fields legitimately carry Float.NaN (no GPS
        // fix yet, no bearing, no altitude). Without this flag every
        // outbound frame fails serialization and the link silently
        // never delivers anything.
        allowSpecialFloatingPointValues = true
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught", t) }
    )

    private val lifecycleLock = Mutex()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var loopJob: Job? = null
    @Volatile private var publishJob: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    private val demo = HudDemoSource()
    @Volatile private var latest: HudState = HudState()

    fun start() {
        scope.launch { lifecycleLock.withLock { doStart() } }
    }

    fun stop() {
        scope.launch { lifecycleLock.withLock { doStop() } }
    }

    private suspend fun doStart() {
        if (loopJob != null) return
        Log.i(TAG, "Starting HUD link")
        if (HudDebug.read("debug.eucplanet.demo") == "true") {
            Log.i(TAG, "Demo telemetry mode is ON")
            demo.start()
        }

        // Capture an initial snapshot synchronously so the first frame
        // sent after the WebSocket opens has live wheel data, not the
        // HudState() defaults. Without this, the rider sees zeros until
        // publishJob completes its first iteration -- and on a cold
        // process where DataStore still has to warm up, that first
        // suspend can take long enough that the WS dial+open completes
        // first and ships a zeros frame.
        try {
            latest = snapshot()
        } catch (t: Throwable) {
            Log.w(TAG, "initial snapshot failed", t)
        }

        publishJob = scope.launch {
            while (true) {
                try {
                    latest = snapshot()
                } catch (t: Throwable) {
                    // Log the stack trace, not just the message --
                    // a silent NPE here was previously masked, leaving
                    // the rider with zeros on the HUD and no signal as
                    // to why.
                    Log.w(TAG, "snapshot failed", t)
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }

        loopJob = scope.launch { dialLoop() }
    }

    private suspend fun doStop() {
        Log.i(TAG, "Stopping HUD link")
        try { demo.stop() } catch (_: Throwable) {}
        try { ws?.close(1000, "stopping") } catch (_: Throwable) {}
        ws = null
        publishJob?.cancel(); publishJob = null
        loopJob?.cancel(); loopJob = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }

    /**
     * Outer loop: re-read the HUD address from settings every iteration so a
     * rider editing the IP doesn't have to toggle the link off and on. Open
     * a WebSocket, pump state until it dies, then back off and retry.
     *
     * Reading settings on each iteration (instead of subscribing to a flow)
     * keeps the loop simple; the cost is a typed read per reconnect, which
     * is negligible.
     */
    private suspend fun dialLoop() {
        var attempt = 0
        while (true) {
            val s = runCatching { settingsRepository.get() }.getOrNull()
            val hudIp = s?.hudIp?.trim().orEmpty()
            // Allow a debug system prop to override settings for emulator
            // testing where there's no UI input device:
            //   adb shell setprop debug.eucplanet.hud.peer 10.0.2.2:28080
            val override = HudDebug.read("debug.eucplanet.hud.peer")?.takeIf { it.isNotBlank() }
            val peer = when {
                override != null -> override
                hudIp.isNotBlank() -> {
                    val port = s?.hudServerPort?.takeIf { it in 1..65535 }
                        ?: HudDiscovery.DEFAULT_PORT
                    "$hudIp:$port"
                }
                // Blank IP: fall back to mDNS so the rider doesn't have to
                // type anything when the HUD is on the same network. The
                // HUD advertises _eucplanet._tcp via JmDNS on its side.
                else -> resolveViaMdns()
            }

            if (peer == null) {
                // No setting, no mDNS hit yet. Wait briefly before the
                // next resolve attempt -- mDNS responses can take a few
                // seconds when multicast is congested.
                delay(2_000L)
                continue
            }
            val ok = streamUntilClosed(peer)
            if (!ok) {
                delay(backoff(attempt++))
            } else {
                attempt = 0
            }
        }
    }

    /** Resolve `_eucplanet._tcp.local.` on whatever subnet we have. Returns
     *  `host:port` of the first matching HUD with a compatible protocol
     *  version, or null if nothing answered within [MDNS_RESOLVE_TIMEOUT_MS].
     *
     *  Each call opens its own JmDNS instance so a network change between
     *  attempts (rider switching hotspot on, walking out of range) gets a
     *  fresh socket bound to the current interface. JmDNS doesn't always
     *  rebind cleanly when the underlying address changes. */
    private suspend fun resolveViaMdns(): String? {
        ensureMulticastLock()
        var md: JmDNS? = null
        try {
            md = JmDNS.create()
            val resolved = kotlinx.coroutines.CompletableDeferred<String?>()
            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    md.requestServiceInfo(event.type, event.name, 1_000L)
                }
                override fun serviceRemoved(event: ServiceEvent) {}
                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val ipv4 = info.inet4Addresses?.firstOrNull()
                    if (ipv4 != null) {
                        val versionOk = (info.getPropertyString(HudDiscovery.TXT_VERSION)
                            ?.toIntOrNull() ?: 1) <= HudState.PROTOCOL_VERSION
                        if (versionOk) {
                            resolved.complete("${ipv4.hostAddress}:${info.port}")
                        }
                    }
                }
            }
            md.addServiceListener(HudDiscovery.SERVICE_TYPE, listener)
            val winner = kotlinx.coroutines.withTimeoutOrNull(MDNS_RESOLVE_TIMEOUT_MS) {
                resolved.await()
            }
            try { md.removeServiceListener(HudDiscovery.SERVICE_TYPE, listener) } catch (_: Throwable) {}
            return winner
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS resolve failed: ${t.message}")
            return null
        } finally {
            try { md?.close() } catch (_: Throwable) {}
        }
    }

    /** Acquire a multicast lock once; JmDNS needs it to join 224.0.0.251 on
     *  the WiFi subnet. Idempotent. */
    private fun ensureMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "multicast lock acquire failed: ${t.message}")
        }
    }

    /** Open one WebSocket and pump frames until it closes. Returns true on
     *  clean close, false on transport error (which gates backoff). */
    private suspend fun streamUntilClosed(peer: String): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val req = Request.Builder()
            .url("ws://$peer${HudDiscovery.PATH_STATE}")
            .build()
        val listener = object : WebSocketListener() {
            private var sendJob: Job? = null
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "HUD link open: $peer")
                ws = webSocket
                // Push a frame every PUBLISH_INTERVAL_MS off the snapshot
                // buffer. We don't dedupe: even when no field changed, the
                // timestamp bump in [snapshot] keeps the HUD's last-frame
                // freshness signal live.
                sendJob = scope.launch {
                    while (true) {
                        val frame = latest
                        try {
                            if (!webSocket.send(json.encodeToString(frame))) {
                                // OkHttp returns false when the outbound
                                // queue is over its bound; treat as transport
                                // failure so we reconnect.
                                Log.w(TAG, "send queue full, closing")
                                webSocket.close(1011, "send-queue-full")
                                break
                            }
                        } catch (t: Throwable) {
                            // Without an explicit close here the dial
                            // loop blocks on done.await() until the
                            // 15s OkHttp ping eventually trips onFailure
                            // -- and during those 15s the rider sees a
                            // "connected but frozen" HUD with no auto
                            // recovery. Force the close so we reconnect
                            // on the next dial-loop iteration.
                            Log.w(TAG, "send failed, closing for reconnect", t)
                            try { webSocket.close(1011, "send-failed") } catch (_: Throwable) {}
                            break
                        }
                        delay(PUBLISH_INTERVAL_MS)
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val cmd = json.decodeFromString<HudCommand>(text)
                    commandSink.dispatch(cmd)
                } catch (t: Throwable) {
                    Log.w(TAG, "bad command: ${t.message}: $text")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "HUD link closing: $code $reason")
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sendJob?.cancel()
                ws = null
                commandSink.onHudDisconnected()
                done.complete(true)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "HUD link failure: ${t.message} (${response?.code})")
                sendJob?.cancel()
                ws = null
                commandSink.onHudDisconnected()
                done.complete(false)
            }
        }
        http.newWebSocket(req, listener)
        return done.await()
    }

    private fun backoff(attempt: Int): Long {
        val expanded = BACKOFF_MIN_MS shl attempt.coerceAtMost(3)
        return expanded.coerceAtMost(BACKOFF_MAX_MS)
    }

    private suspend fun snapshot(): HudState {
        val s = settingsRepository.get()
        val wd = wheelRepository.wheelData.value
        val state = wheelRepository.connectionState.value
        val nav = navigationEngine.navState.value
        val location = tripRepository.currentLocation.value
        val external = externalGpsRepository.currentSample.value

        val effTilt = if (wheelRepository.safetySpeedActive.value)
            s.safetyTiltbackKmh else s.tiltbackSpeedKmh
        val gaugeMax = (((effTilt / 10f).toInt() + 1) * 10f).coerceAtLeast(30f)

        val externalFresh = external != null &&
            System.currentTimeMillis() - external.timestamp < 5_000L
        val gpsSpeedPair: Pair<Float, String>? = when {
            s.gpsPrioritizeExternal && externalFresh ->
                external!!.speedKmh to "EXTERNAL"
            location != null && location.hasSpeed() ->
                (location.speed * 3.6f) to "PHONE"
            !s.gpsPrioritizeExternal && externalFresh ->
                external!!.speedKmh to "EXTERNAL"
            else -> null
        }

        val navShow = nav.active && !nav.minimized && nav.cueVisible

        val d = if (demo.active) demo.frame else null

        // Debug-only protocol-version overrides so a tester can drive
        // the version-mismatch UI on a single APK pair without rebuilding.
        // Set via:
        //   adb shell setprop debug.eucplanet.proto.major 2
        //   adb shell setprop debug.eucplanet.proto.minor 5
        //   adb shell am force-stop com.eried.eucplanet
        // Wiped on reboot. Defaults preserve the real PROTOCOL_MAJOR /
        // PROTOCOL_MINOR so production riders never see this.
        val overrideMajor = HudDebug.read("debug.eucplanet.proto.major")
            ?.toIntOrNull() ?: HudState.PROTOCOL_MAJOR
        val overrideMinor = HudDebug.read("debug.eucplanet.proto.minor")
            ?.toIntOrNull() ?: HudState.PROTOCOL_MINOR

        return HudState(
            protocolVersion = overrideMajor,
            protocolMajor = overrideMajor,
            protocolMinor = overrideMinor,
            connected = d != null || state == ConnectionState.CONNECTED,
            wheelName = if (d != null) "Demo Wheel" else (wheelRepository.modelName.value ?: ""),
            speedKmh = d?.speedKmh ?: wd.speed,
            batteryPercent = d?.batteryPct ?: wd.batteryPercent,
            voltage = d?.voltage ?: wd.voltage,
            current = d?.current ?: wd.current,
            pwm = d?.pwm ?: wd.pwm,
            temperatureC = d?.tempC ?: wd.maxTemperature,
            tripKm = d?.tripKm ?: wd.tripDistance,
            torque = wd.torque,
            lightOn = wd.lightOn,
            gaugeMaxKmh = gaugeMax,
            gaugeOrangeThresholdPct = s.gaugeOrangeThresholdPct,
            gaugeRedThresholdPct = s.gaugeRedThresholdPct,
            showGaugeColorBand = if (d != null) true else s.showGaugeColorBand,
            unitSpeed = com.eried.eucplanet.util.Units.effectiveSpeedUnit(s),
            unitDistance = com.eried.eucplanet.util.Units.effectiveDistanceUnit(s),
            unitTemp = com.eried.eucplanet.util.Units.effectiveTempUnit(s),
            accentArgb = resolveAccentArgb(s),
            latitude = (location?.latitude ?: 0.0) + (d?.dLat ?: 0.0),
            longitude = (location?.longitude ?: 0.0) + (d?.dLng ?: 0.0),
            gpsSpeedKmh = gpsSpeedPair?.first ?: Float.NaN,
            gpsSource = gpsSpeedPair?.second ?: "",
            gpsHasFix = location != null,
            gpsHeadingDeg = if (location?.hasBearing() == true) location.bearing
                else Float.NaN,
            gpsAltitudeM = if (location?.hasAltitude() == true) location.altitude.toFloat()
                else Float.NaN,
            wheelRollDeg = wd.rollAngle,
            wheelPitchDeg = wd.pitchAngle,
            customOverlayJson = s.hudCustomOverlayJson,
            navActive = d?.navActive ?: navShow,
            navArrowAngleDeg = d?.navAngleDeg ?: nav.arrowAngleDeg(),
            navPrimary = d?.navPrimary ?: nav.primaryText,
            navDistance = d?.navDistance ?: nav.distanceText,
            navArrived = d?.navArrived ?: nav.arrived,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun resolveAccentArgb(s: AppSettings): String {
        val accent = AccentOptions.firstOrNull { it.key == s.accentColor }?.color ?: AccentTeal
        val argb = accent.value.toLong().ushr(32).toInt()
        return "#%08X".format(argb)
    }
}
