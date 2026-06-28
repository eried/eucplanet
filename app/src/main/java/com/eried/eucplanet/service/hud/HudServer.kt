package com.eried.eucplanet.service.hud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.RadarRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDebug
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.protocol.RadarTargetWire
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.ui.theme.AccentOptions
import com.eried.eucplanet.ui.theme.AccentTeal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
    private val radarRepository: RadarRepository,
    private val navigationEngine: NavigationEngine,
    private val commandSink: HudCommandSink,
    private val themeController: com.eried.eucplanet.ui.theme.ThemeController,
    val udpListener: HudUdpListener,
    private val subnetProbe: HudSubnetProbe,
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
        /** Aggressive-retry window after the link is enabled. During this
         *  period the dial loop bottoms out at a 2 s tick so a freshly
         *  reachable HUD is grabbed within a few seconds. After the window
         *  expires the tick relaxes to 5 s. */
        private const val DISCOVERY_SPRINT_MS = 30_000L
        // Default carousel order shipped with all 12 known screens.
        // Mirrors SettingsViewModel.defaultEnabledHudScreens so a fresh
        // install gets a non-empty wire field on the first frame --
        // lets the HUD distinguish "no frame received yet" (empty)
        // from "rider's choice" (non-empty). Updated from the original
        // 7 after preview-3 tester feedback that the opt-in screens
        // were invisible to most testers.
        private val DEFAULT_HUD_SCREENS = listOf(
            "Dashboard", "Camera", "Telemetry",
            "Custom", "CustomCam", "Map", "Nav",
            "Power", "TripStats", "Compass", "Safety", "BigClock"
        )
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
        // 5s, not 15s: a frozen/half-open HUD keeps the TCP socket open, so
        // outbound frame sends succeed into the OS buffer and never error --
        // only a missed pong reveals the dead peer. On the local hotspot link
        // (sub-10ms RTT) a 5s ping detects a dead HUD in ~5s and triggers the
        // dial-loop's rediscover+reconnect, instead of the rider staring at a
        // frozen HUD for 15s+. Cheap on a LAN.
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var loopJob: Job? = null
    @Volatile private var publishJob: Job? = null
    @Volatile private var ws: WebSocket? = null

    /**
     * Cancels any pending dial-loop backoff the moment something interesting
     * happens at the network layer (rider's phone hopped from a hotspot to
     * home WiFi, came back from airplane mode, etc.). Without this the loop
     * sits in its 5 s idle tick before noticing the network changed, and the
     * rider sees a 5 s gap when their phone briefly disconnects.
     *
     * Capacity 1 + drop-oldest: only the FACT that a kick happened matters,
     * not how many times. Send / receive never blocks the network callback
     * thread.
     */
    private val reconnectKick = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Currently-registered ConnectivityManager callback; cleared on doStop. */
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // Watch the toggle ourselves so the link starts the moment the
        // rider flips `Enable data link` -- no dependency on WheelService
        // being alive. WheelService still gets to call start/stop too
        // (older code paths); start() and stop() are idempotent so the
        // races are harmless.
        scope.launch {
            var on = false
            settingsRepository.settings.collect { s ->
                val want = s.hudServerEnabled ||
                    com.eried.eucplanet.hud.protocol.HudDebug.read("debug.eucplanet.hud.force") == "true"
                if (want != on) {
                    if (want) start() else stop()
                    on = want
                }
            }
        }
    }
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    /** High-performance WiFi lock acquired while the HUD link is enabled.
     *  Without this, the radio enters DTIM power-save once the screen is off
     *  or the app loses foreground priority, and OkHttp's 15 s ping starts
     *  landing in a radio-sleep window. Surfaces as "Software caused
     *  connection abort" / "ping but didn't receive pong" every 30-60 s on
     *  the rider's HUD discovery log even though the route is fine. The
     *  multicast lock is for inbound mDNS only; it does NOT pin the radio
     *  out of power-save. Small power cost (a few mA), eliminates the
     *  background-throttling drops entirely. */
    @Volatile private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Which discovery channel produced the address that opened the current
     * WebSocket. Surfaced on the HUD settings screen as a "Connected via:"
     * status line so the rider can tell whether the manual hint is even
     * being used or whether auto-discovery did the job.
     */
    enum class ConnectionSource { UDP_BEACON, MDNS, MANUAL, SUBNET_PROBE, DEBUG_OVERRIDE, NONE }
    private val _connectionSource =
        kotlinx.coroutines.flow.MutableStateFlow(ConnectionSource.NONE)
    val connectionSource: kotlinx.coroutines.flow.StateFlow<ConnectionSource>
        get() = _connectionSource

    /**
     * Discovery / dial-layer trace. Always written to logcat for OEM-side
     * debugging; piped through [DiagnosticsLogger] as NOTE entries so a
     * Service Mode capture has the full story (probe started, no answer,
     * dial attempt, WS connected, etc.) without us cluttering the Settings
     * page with a permanent log widget that's only useful while debugging.
     *
     * Riders never opened Service Mode for normal use, so this stays cheap:
     * `DiagnosticsLogger.note` is a no-op when the logger isn't enabled.
     */
    private fun log(msg: String) {
        Log.i(TAG, "[disc] $msg")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("hud_link: $msg")
    }

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
        log("Link enabled, starting discovery")
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

        // UDP beacon listener only runs when auto-discovery is enabled.
        // When the rider has turned auto-discovery off they explicitly do
        // not want any background "find" activity -- only the manual IP
        // they typed should be dialled.
        val s = runCatching { settingsRepository.get() }.getOrNull()
        if (s?.hudAutoDiscover == true) {
            udpListener.start()
            log("Auto-discovery on (UDP + mDNS + subnet + manual)")
        } else {
            val ip = s?.hudIp?.trim().orEmpty()
            val port = s?.hudServerPort?.takeIf { it in 1..65535 }
                ?: HudDiscovery.DEFAULT_PORT
            val target = if (ip.isBlank()) "(no IP set)" else "$ip:$port"
            log("Auto-discovery off, manual only -> $target")
        }
        acquireWifiPerfLock()
        registerNetworkCallback()
        loopJob = scope.launch { dialLoop() }
    }

    private suspend fun doStop() {
        Log.i(TAG, "Stopping HUD link")
        log("Link disabled")
        try { demo.stop() } catch (_: Throwable) {}
        try { ws?.close(1000, "stopping") } catch (_: Throwable) {}
        ws = null
        publishJob?.cancel(); publishJob = null
        loopJob?.cancel(); loopJob = null
        udpListener.stop()
        _connectionSource.value = ConnectionSource.NONE
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
        try { wifiLock?.release() } catch (_: Throwable) {}
        wifiLock = null
        unregisterNetworkCallback()
    }

    /**
     * Watch the system for network availability changes while the link is
     * enabled. Two reactions:
     *
     *  - `onAvailable(network)`: kick the dial loop so a pending backoff /
     *    idle tick gets cancelled and we immediately try to re-discover the
     *    HUD on the new network. Closes the 1-3 s gap riders saw when their
     *    phone hopped from a hotspot to home WiFi mid-session.
     *  - `onLosing(network, msToLive)`: Android has decided this network is
     *    going away soon. Close the WebSocket cleanly now so the next attempt
     *    starts from a clean slate instead of waiting for OkHttp to surface
     *    "Software caused connection abort" on the radio teardown.
     *
     * Filter for WiFi-internet-capable networks so we don't react to every
     * cellular or Bluetooth tether handoff that doesn't touch our reachability.
     */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network onAvailable: kicking dial loop")
                log("Network came up, retrying immediately")
                reconnectKick.trySend(Unit)
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                Log.i(TAG, "network onLosing (${maxMsToLive}ms): closing WS preemptively")
                log("Network dropping in ${maxMsToLive}ms, closing WS")
                try { ws?.close(1000, "network-losing") } catch (_: Throwable) {}
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            netCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed: ${t.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    /** Delay up to [ms], returning early when a network-availability kick
     *  arrives via [reconnectKick]. Used by the dial loop in place of plain
     *  `delay()` so a network change cancels any pending backoff. */
    private suspend fun delayOrKick(ms: Long) {
        if (ms <= 0L) return
        select<Unit> {
            onTimeout(ms) { }
            reconnectKick.onReceive { }
        }
    }

    /** Acquire a high-performance WiFi lock so the radio stays out of
     *  DTIM power-save while the HUD link is enabled. Idempotent and
     *  best-effort: if the lock can't be acquired we still run, the link
     *  just risks the background-throttling drops. */
    private fun acquireWifiPerfLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "eucplanet-hud-link"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "wifi perf lock acquire failed: ${t.message}")
        }
    }

    /**
     * Outer loop: discover-or-read the HUD address, open a WebSocket, pump
     * state until it dies, back off, retry.
     *
     * When [AppSettings.hudAutoDiscover] is ON (default), we walk a
     * priority chain on each iteration:
     *
     *   1. UDP beacon sighting (freshest first; cheap, the most reliable
     *      channel because it works on hotspots that block multicast)
     *   2. mDNS browse on `_eucplanet._tcp.local.` (5 s wait)
     *   3. Manual `hudIp` from settings (treated as a last-known hint
     *      rather than the only truth)
     *   4. /24 subnet probe of the phone's own IP (slow, ~3 s, only fires
     *      when the first three failed)
     *
     * When auto-discover is OFF we fall back to the legacy single-path
     * behaviour: manual IP only. That mode exists as an escape hatch for
     * the very rare environment where all three auto channels mislead us.
     *
     * Each attempt's source is published on [connectionSource] so the
     * settings screen can show "Connected via: UDP beacon" and the rider
     * has a one-glance read on which channel did the work.
     */
    private suspend fun dialLoop() {
        var attempt = 0
        val sprintStartedAtMs = System.currentTimeMillis()
        while (true) {
            // Re-assert the WiFi lock every tick: it's only acquired once at
            // enable, but the OS can reclaim it (or it can lapse on a network
            // hop). Idempotent -- keeps the radio out of power-save for the
            // whole session, not just the instant the link came up.
            acquireWifiPerfLock()
            val s = runCatching { settingsRepository.get() }.getOrNull()
            val override = HudDebug.read("debug.eucplanet.hud.peer")?.takeIf { it.isNotBlank() }
            val autoDiscover = s?.hudAutoDiscover ?: true
            val manualIp = s?.hudIp?.trim().orEmpty()
            val manualPort = s?.hudServerPort?.takeIf { it in 1..65535 }
                ?: HudDiscovery.DEFAULT_PORT

            val (peer, source) = when {
                override != null -> override to ConnectionSource.DEBUG_OVERRIDE
                !autoDiscover -> resolveManualOnly(s, manualIp, manualPort)
                else -> resolvePeer(manualIp, manualPort)
            }

            if (peer == null) {
                _connectionSource.value = ConnectionSource.NONE
                // Sprint mode: aggressively retry every 2 s for the first
                // 30 s after the dial loop kicks off so the rider gets a
                // fast connection on a healthy network. After the sprint
                // window we relax to a 5 s tick to be kinder to the radio
                // when the network is genuinely broken. A NetworkCallback
                // kick (phone hopped to a different WiFi, came back from
                // airplane mode) cancels whichever delay is pending so we
                // retry immediately instead of waiting out the tick.
                val sinceStart = System.currentTimeMillis() - sprintStartedAtMs
                delayOrKick(if (sinceStart < DISCOVERY_SPRINT_MS) 2_000L else 5_000L)
                continue
            }
            _connectionSource.value = source
            val ok = streamUntilClosed(peer)
            if (!ok) {
                delayOrKick(backoff(attempt++))
            } else {
                attempt = 0
            }
        }
    }

    /**
     * Run the discovery priority chain and return the first usable
     * `host:port` plus the source it came from. Each step's timing is
     * tuned so the full chain bottoms out in ~10 s even in the worst case
     * (no UDP, no mDNS, no manual hint, subnet probe runs).
     */
    /**
     * Resolve a peer when auto-discovery is OFF. We trust whatever the rider
     * typed, fill in only what's missing with mDNS (no UDP, no subnet probe
     * - the rider has explicitly said they don't want the full sweep):
     *  - IP set, port set:      dial as-is
     *  - IP set, port missing:  use default 28080
     *  - port set, IP missing:  resolve IP via mDNS, attach the typed port
     *  - both missing:          resolve full host:port via mDNS
     */
    private suspend fun resolveManualOnly(
        s: com.eried.eucplanet.data.model.AppSettings?,
        manualIp: String,
        manualPort: Int,
    ): Pair<String?, ConnectionSource> {
        val typedPort = s?.hudServerPort?.takeIf { it in 1..65535 }
        if (manualIp.isNotBlank()) {
            // Always honour the typed IP. Port falls back to default 28080
            // when the rider left it blank / invalid.
            return "$manualIp:$manualPort" to ConnectionSource.MANUAL
        }
        // IP not set: rely on mDNS for the address, attach the typed port
        // if the rider set one.
        log("No IP set, falling back to mDNS for the address")
        val resolved = resolveViaMdns() ?: run {
            log("mDNS: no answer")
            return null to ConnectionSource.NONE
        }
        // resolveViaMdns returns "host:port". If the rider typed a port we
        // override the mDNS-reported one with theirs.
        val finalPeer = if (typedPort != null) {
            val justHost = resolved.substringBefore(':')
            "$justHost:$typedPort"
        } else resolved
        log("mDNS resolved address: $finalPeer")
        return finalPeer to ConnectionSource.MDNS
    }

    /**
     * Race all discovery channels in parallel and return the first one
     * that produces a usable address. Whichever channel answers first
     * wins; the others get cancelled immediately so we don't waste any
     * radio time after we already know where to dial.
     *
     * Channels fired together:
     *  - UDP beacon (instant if the listener has a fresh cached sighting,
     *    otherwise blocks waiting for the next packet up to a soft cap)
     *  - mDNS browse (5 s timeout, common on real WiFi)
     *  - Subnet probe across the phone's own /24 (~3 s on fast LAN)
     *  - Manual IP, fired after a short grace period so the first three
     *    have a chance to win cleanly when they will
     *
     * This is the "try harder" the rider asked for: instead of stepping
     * through sequentially and waiting for each to give up before the
     * next one starts, every channel runs at once. Bottoms out at the
     * SLOWEST channel only when none of them find a HUD.
     */
    private suspend fun resolvePeer(
        manualIp: String,
        manualPort: Int,
    ): Pair<String?, ConnectionSource> = kotlinx.coroutines.coroutineScope {
        log("Searching (all channels in parallel)…")
        val results = kotlinx.coroutines.channels.Channel<Pair<String, ConnectionSource>>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )

        val udpJob = launch {
            // Check the listener's cache repeatedly so a freshly-arrived
            // beacon shows up within ~200 ms instead of waiting for the
            // dial loop's next iteration. Bounded so we eventually give
            // up if the other channels are also losing.
            val until = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < until) {
                val s = udpListener.latest.value
                if (s != null && System.currentTimeMillis() - s.receivedAtMs < 10_000L) {
                    log("UDP beacon: ${s.ip}:${s.port}")
                    results.send("${s.ip}:${s.port}" to ConnectionSource.UDP_BEACON)
                    return@launch
                }
                kotlinx.coroutines.delay(200L)
            }
            log("UDP beacon: no broadcast received")
        }

        val mdnsJob = launch {
            log("mDNS: browsing _eucplanet._tcp.local…")
            val v = resolveViaMdns()
            if (v != null) {
                log("mDNS: found $v")
                results.send(v to ConnectionSource.MDNS)
            } else {
                log("mDNS: no answer in ${MDNS_RESOLVE_TIMEOUT_MS / 1000}s")
            }
        }

        val probeJob = launch {
            log("Subnet probe: scanning /24…")
            val ip = subnetProbe.probe(manualPort)
            if (ip != null) {
                log("Subnet probe: $ip:$manualPort answered")
                results.send("$ip:$manualPort" to ConnectionSource.SUBNET_PROBE)
            } else {
                log("Subnet probe: nothing answered")
            }
        }

        val manualJob = if (manualIp.isNotBlank()) {
            launch {
                // Small grace period so a healthy UDP / mDNS hit wins the
                // race before we fall back to a possibly-stale manual IP.
                kotlinx.coroutines.delay(1_500L)
                log("Manual hint: $manualIp:$manualPort")
                results.send("$manualIp:$manualPort" to ConnectionSource.MANUAL)
            }
        } else null

        val allJobs = listOfNotNull(udpJob, mdnsJob, probeJob, manualJob)
        val winner = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            results.receive()
        }
        allJobs.forEach { it.cancel() }
        if (winner != null) winner else null to ConnectionSource.NONE
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
        log("Dial ws://$peer/state")
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val req = Request.Builder()
            .url("ws://$peer${HudDiscovery.PATH_STATE}")
            .build()
        val listener = object : WebSocketListener() {
            private var sendJob: Job? = null
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "HUD link open: $peer")
                log("Connected to $peer ✓")
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
                log("Not found: ${t.message ?: t::class.simpleName}")
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

        // Rear-view radar (Varia). In demo mode the synthetic source supplies
        // scripted cars; otherwise read the live frame. "connected" gates the
        // HUD radar widget between "idle / no radar" and "lane clear".
        val radarFrame = radarRepository.currentFrame.value
        val radarLive = radarRepository.connectionState.value == ConnectionState.CONNECTED

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
            totalKm = d?.totalKm ?: wd.totalDistance,
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
            enabledHudScreens = run {
                // Carousel order = the rider's preferred row order,
                // filtered to keep only the enabled screens. Both
                // fields are independent in storage so toggling a row
                // off doesn't move it in the Personalize list.
                val order = s.hudScreensOrder.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val enabled = s.hudScreensEnabled.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                // ALWAYS ship a non-empty list. The HUD treats an empty
                // enabledHudScreens as "no wire frame received yet" and
                // shows a waiting splash; if the rider hasn't customised
                // we ship the default seven so the HUD has something to
                // render the moment a real frame arrives.
                if (order.isEmpty() && enabled.isEmpty()) {
                    DEFAULT_HUD_SCREENS
                } else if (order.isEmpty()) {
                    enabled.toList()
                } else {
                    order.filter { it in enabled }
                        .ifEmpty { DEFAULT_HUD_SCREENS }
                }
            },
            hudMapStyle = s.hudMapStyle,
            hudMapContrastPct = s.hudMapContrastPct,
            hudMapBrightnessPct = s.hudMapBrightnessPct,
            navActive = d?.navActive ?: navShow,
            navArrowAngleDeg = d?.navAngleDeg ?: nav.arrowAngleDeg(),
            navPrimary = d?.navPrimary ?: nav.primaryText,
            navDistance = d?.navDistance ?: nav.distanceText,
            navArrived = d?.navArrived ?: nav.arrived,
            // Human-readable labels for the HUD joystick long-press guide.
            // The HUD has no access to the phone's action config, so resolve
            // each configured slot key to its catalog label here. Reuses the
            // already-read settings `s`; no extra IO in the publish loop.
            joystickUp = joystickLabel(s.hudActionUp),
            joystickDown = joystickLabel(s.hudActionDown),
            joystickLeft = joystickLabel(s.hudActionLeft),
            joystickRight = joystickLabel(s.hudActionRight),
            radarConnected = if (d != null) d.radarConnected else radarLive,
            radarBatteryPercent = if (d != null) d.radarBatteryPercent
                else (radarFrame?.batteryPercent ?: -1),
            radarTargets = if (d != null) d.radarTargets
                else radarFrame?.threats?.take(8)?.map {
                    RadarTargetWire(
                        id = it.id,
                        distanceM = it.distanceM,
                        approachSpeedKmh = it.approachSpeedKmh,
                        level = it.threatLevel.ordinal
                    )
                }.orEmpty(),
            timestampMs = System.currentTimeMillis()
        )
    }

    /** Resolve a configured joystick-slot action [key] to its human-readable
     *  catalog label. "" / "NONE" (the unset sentinel) and any key the catalog
     *  doesn't know map to "" so the HUD draws that direction as unset. */
    private fun joystickLabel(key: String): String {
        if (key.isBlank() || key == "NONE") return ""
        val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key) ?: return ""
        return context.getString(spec.labelRes)
    }

    private fun resolveAccentArgb(s: AppSettings): String =
        com.eried.eucplanet.ui.theme.ThemeAccent.primaryArgb(themeController.activeColors.value)
}
