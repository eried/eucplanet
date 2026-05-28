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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

/**
 * In-app HTTP/SSE server that feeds an external HUD companion app over the
 * phone's WiFi hotspot.
 *
 * Lifecycle is owned by [com.eried.eucplanet.service.WheelService]. The
 * server stays bound only while the rider is actively using the wheel; we
 * never want a lingering listening socket draining the radio when the app is
 * idle. Toggle via [AppSettings.hudServerEnabled].
 *
 * Wire format: [HudState] published as Server-Sent Events at /state once per
 * [PUBLISH_INTERVAL_MS]. Commands flow the other way via POST /command. mDNS
 * advertises the service as `_eucplanet._tcp` so the HUD can auto-pair
 * without the rider typing an IP.
 *
 * Parallels [com.eried.eucplanet.wear.WearBridge] philosophically — same
 * field selection — but the transport is TCP-on-WiFi instead of the Wearable
 * Data Layer because the HUD is a generic Android device, not a Wear OS one.
 */
@Singleton
class HudServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val externalGpsRepository: ExternalGpsRepository,
    private val tripRepository: TripRepository,
    private val navigationEngine: NavigationEngine,
    private val commandSink: HudCommandSink,
    private val tileFetcher: TileFetcher
) {

    companion object {
        private const val TAG = "HudServer"
        // 5 Hz: same rate WearBridge uses. Smooth speed needle without
        // saturating the hotspot, and matches the BLE poll rate floor so the
        // HUD never starves itself waiting for a new frame.
        private const val PUBLISH_INTERVAL_MS = 200L
        // Fixed multicast lock tag for diagnostics; JmDNS needs the lock so
        // the phone's WiFi hotspot subnet sees its own mDNS group join.
        private const val MULTICAST_LOCK_TAG = "eucplanet-hud-mdns"
        // How often the mDNS watchdog rechecks for a better bind address.
        // 5 s is responsive enough that a rider toggling hotspot ON gets
        // discoverable in well under the SSE reconnect window.
        private const val WATCHDOG_INTERVAL_MS = 5_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var serverJob: Job? = null
    @Volatile private var publishJob: Job? = null
    @Volatile private var engine: io.ktor.server.engine.ApplicationEngine? = null
    @Volatile private var jmdns: JmDNS? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var serverPort: Int = HudDiscovery.DEFAULT_PORT

    /** Latest [HudState] computed by the publisher loop. SSE handlers read
     *  this volatile reference rather than holding their own subscription, so
     *  late-connecting HUDs immediately get a current snapshot. */
    @Volatile private var latest: HudState = HudState()

    /** Debug-only fake telemetry source. Lazily constructed; remains
     *  inactive until [start] sees `debug.eucplanet.demo=true`. */
    private val demo = HudDemoSource()

    fun start() {
        if (serverJob != null) return
        Log.i(TAG, "Starting HUD server")
        if (HudDebug.read("debug.eucplanet.demo") == "true") {
            Log.i(TAG, "Demo telemetry mode is ON")
            demo.start()
        }

        serverJob = scope.launch {
            try {
                val port = runCatching { settingsRepository.get().hudServerPort }
                    .getOrDefault(HudDiscovery.DEFAULT_PORT)
                    .takeIf { it in 1024..65535 } ?: HudDiscovery.DEFAULT_PORT
                serverPort = port

                engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(StatusPages) {
                        // CIO + SSE write loops can blow up with a generic IOException
                        // when the HUD walks away from the hotspot. Swallow them at
                        // the framework level so the server keeps serving the next
                        // client instead of taking the whole module down.
                        exception<Throwable> { call, cause ->
                            Log.d(TAG, "HTTP exception on ${call.request.local.uri}: ${cause.message}")
                            runCatching {
                                call.respond(HttpStatusCode.InternalServerError)
                            }
                        }
                    }
                    routing {
                        get(HudDiscovery.PATH_HEALTH) {
                            call.respondText("eucplanet-hud ok", contentType = io.ktor.http.ContentType.Text.Plain)
                        }
                        get(HudDiscovery.PATH_STATE) {
                            // Server-Sent Events: a long-lived response that
                            // pushes one JSON-encoded HudState per cycle. CIO
                            // closes the writer when the client disconnects,
                            // which yields a CancellationException out of the
                            // suspending write — propagated, caught above.
                            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                            call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                            call.respondTextWriter(
                                contentType = io.ktor.http.ContentType.Text.EventStream
                            ) {
                                while (true) {
                                    val frame = latest
                                    write("data: ")
                                    write(json.encodeToString(frame))
                                    write("\n\n")
                                    flush()
                                    delay(PUBLISH_INTERVAL_MS)
                                }
                            }
                        }
                        get("${HudDiscovery.PATH_TILES_PREFIX}/{z}/{x}/{y}") {
                            // Pass-through raster tile proxy. The HUD has no
                            // mobile data of its own; routing tiles through
                            // the phone keeps the HUD usable as long as the
                            // phone has a connection. Failures map to 502 so
                            // the HUD's blank-tile fallback shows clearly.
                            val z = call.parameters["z"]?.toIntOrNull()
                            val x = call.parameters["x"]?.toIntOrNull()
                            val y = call.parameters["y"]?.toIntOrNull()
                            if (z == null || x == null || y == null ||
                                z !in 0..19 || x < 0 || y < 0
                            ) {
                                call.respond(HttpStatusCode.BadRequest)
                                return@get
                            }
                            try {
                                val bytes = tileFetcher.fetch(z, x, y)
                                if (bytes == null) {
                                    call.respond(HttpStatusCode.BadGateway)
                                } else {
                                    call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "tile $z/$x/$y failed: ${t.message}")
                                call.respond(HttpStatusCode.BadGateway)
                            }
                        }
                        post(HudDiscovery.PATH_COMMAND) {
                            val body = call.receiveText()
                            try {
                                val cmd = json.decodeFromString<HudCommand>(body)
                                commandSink.dispatch(cmd)
                                call.respond(HttpStatusCode.NoContent)
                            } catch (t: Throwable) {
                                Log.w(TAG, "bad command: ${t.message}: $body")
                                call.respond(HttpStatusCode.BadRequest, "bad command")
                            }
                        }
                    }
                }.start(wait = false)

                advertiseMdns(port)
                Log.i(TAG, "HUD server listening on :$port")
            } catch (t: Throwable) {
                Log.e(TAG, "HUD server start failed", t)
            }
        }

        publishJob = scope.launch {
            while (true) {
                try {
                    latest = snapshot()
                } catch (t: Throwable) {
                    Log.w(TAG, "snapshot failed: ${t.message}")
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }

        // mDNS watchdog: the TCP socket binds to 0.0.0.0 so it accepts new
        // interfaces (e.g. the rider enabling hotspot after toggling the
        // HUD server on) automatically, but the mDNS advertisement is
        // one-shot at server start. Re-register every WATCHDOG_INTERVAL_MS
        // whenever the best-available IPv4 address differs from what we
        // last advertised, so a freshly-enabled hotspot becomes
        // discoverable within a few seconds without the rider restarting
        // the app.
        scope.launch {
            var lastBind: InetAddress? = null
            while (true) {
                try {
                    val current = pickHotspotAddress()
                    if (current != lastBind && current != null) {
                        Log.i(TAG, "mDNS bind changed (${lastBind?.hostAddress} -> ${current.hostAddress}), re-registering")
                        readvertiseMdns(serverPort, current)
                        lastBind = current
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "mDNS watchdog tick failed: ${t.message}")
                }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        // Move every blocking shutdown call OFF the caller thread. The
        // caller is WheelService's settings-collect coroutine, which runs
        // on Main; jmdns.close() and engine.stop() can each take a full
        // second on a slow device, and together they freeze the toggle UI
        // long enough that the rider thinks the app crashed. Snapshot the
        // references, null the fields synchronously so the next start()
        // sees a fresh slate, and let the IO coroutine drain the old
        // instances in background.
        Log.i(TAG, "Stopping HUD server")
        val oldJmdns = jmdns
        val oldLock = multicastLock
        val oldEngine = engine
        val oldPublishJob = publishJob
        val oldServerJob = serverJob
        jmdns = null
        multicastLock = null
        engine = null
        publishJob = null
        serverJob = null
        scope.launch {
            try { demo.stop() } catch (_: Throwable) {}
            try { oldJmdns?.unregisterAllServices() } catch (_: Throwable) {}
            try { oldJmdns?.close() } catch (_: Throwable) {}
            try { oldLock?.release() } catch (_: Throwable) {}
            try { oldEngine?.stop(500L, 1000L) } catch (_: Throwable) {}
            oldPublishJob?.cancel()
            oldServerJob?.cancel()
        }
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

        // Nav screen mirrors the phone popup verbatim: active while a route
        // is running and the cue is on the phone's screen. The HUD has a
        // dedicated screen for nav so there's no risk of "clutter" the way
        // the watch dial has; if a rider doesn't want nav they just don't
        // switch to that screen.
        val navShow = nav.active && !nav.minimized && nav.cueVisible

        // Demo overlay: if the synthetic source is running, replace the
        // wheel + nav fields with its scripted values while keeping the
        // rider's real settings (units, accent, gauge thresholds) intact.
        // The map screen still uses real GPS so a real fix is visible; demo
        // adds a small orbit offset so the rider marker drifts.
        val d = if (demo.active) demo.frame else null

        return HudState(
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
            // Force the gauge colour band visible in demo mode so the
            // synthetic PWM sweep through orange/red has something to colour.
            // In production this stays whatever the rider has set.
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
            navActive = d?.navActive ?: navShow,
            navArrowAngleDeg = d?.navAngleDeg ?: nav.arrowAngleDeg(),
            navPrimary = d?.navPrimary ?: nav.primaryText,
            navDistance = d?.navDistance ?: nav.distanceText,
            navArrived = d?.navArrived ?: nav.arrived,
            timestampMs = System.currentTimeMillis()
        )
    }


    private fun resolveAccentArgb(s: AppSettings): String {
        // Mirror the same lookup the phone theme does, but render to a hex
        // string the HUD can parse without pulling in :app's theme module.
        val accent = AccentOptions.firstOrNull { it.key == s.accentColor }?.color ?: AccentTeal
        val argb = accent.value.toLong().ushr(32).toInt()
        return "#%08X".format(argb)
    }

    private fun advertiseMdns(port: Int) {
        // First-time setup: acquire the multicast lock once (JmDNS needs it
        // to join 224.0.0.251 on the phone's hotspot subnet), then advertise.
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "multicast lock acquire failed", t)
        }
        readvertiseMdns(port, pickHotspotAddress())
    }

    /**
     * (Re-)register the mDNS service on the given bind address. Safe to call
     * repeatedly: any prior advertisement is torn down first. Called once at
     * server start and on every watchdog tick where the bind address changed,
     * so a rider enabling hotspot AFTER toggling the HUD server on still gets
     * mDNS discovery within a few seconds without restarting the app.
     */
    private fun readvertiseMdns(port: Int, bind: InetAddress?) {
        // Tear down the prior JmDNS instance so we don't leak a thread per
        // re-advertise. Failures here are non-fatal: a half-closed JmDNS
        // would still be replaced by the create() below.
        try { jmdns?.unregisterAllServices() } catch (_: Throwable) {}
        try { jmdns?.close() } catch (_: Throwable) {}
        jmdns = null
        try {
            jmdns = if (bind != null) JmDNS.create(bind) else JmDNS.create()
            val txt = mapOf(
                HudDiscovery.TXT_VERSION to HudState.PROTOCOL_VERSION.toString(),
                HudDiscovery.TXT_STATE_PATH to HudDiscovery.PATH_STATE,
                HudDiscovery.TXT_COMMAND_PATH to HudDiscovery.PATH_COMMAND,
                HudDiscovery.TXT_TILES_PATH to HudDiscovery.PATH_TILES_PREFIX
            )
            val info = ServiceInfo.create(
                HudDiscovery.SERVICE_TYPE, "EUC Planet", port, 0, 0, txt
            )
            jmdns?.registerService(info)
            Log.i(TAG, "mDNS registered ${info.qualifiedName} on $bind:$port")
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS advertise failed", t)
        }
    }

    private fun pickHotspotAddress(): InetAddress? = try {
        val ifs = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        ifs.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
                    addr is java.net.Inet4Address
            }
    } catch (_: Throwable) {
        null
    }
}
