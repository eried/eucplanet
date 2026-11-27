package com.eried.eucplanet.service.hud

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.HudDiscoveryMode
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.RadarRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudScreens
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.protocol.RadarTargetWire
import com.eried.eucplanet.hud.protocol.StaleLink
import com.eried.eucplanet.hud.protocol.WifiInterferenceDetector
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.format
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
 *
 * One copy for both phones. What differs under it (the WebSocket client,
 * the mDNS browse, the beacon listener, the Wi-Fi and the tile listener) is
 * behind the ports in HudLinkPorts.kt.
 */
class HudServer(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val externalGpsRepository: ExternalGpsRepository,
    private val tripMeterRepository: com.eried.eucplanet.data.repository.TripMeterRepository,
    private val radarRepository: RadarRepository,
    private val navigationEngine: NavigationEngine,
    private val commandSink: HudCommandSink,
    private val themeController: com.eried.eucplanet.ui.theme.ThemeController,
    private val phone: HudPhone,
    private val sockets: HudSockets,
    private val mdns: HudMdns,
    val udpListener: HudBeacons,
    private val network: HudNetwork,
    private val subnetProbe: HudSubnetProbe,
    /** The HUD's map pictures, served while the link is on. */
    private val tileServer: HudTiles,
) {

    companion object {
        private const val TAG = "HudServer"
        // Reconnect backoff: 1s, 2s, 4s, capped at 5s. Below the cap the
        // rider gets a fresh try every time they walk back into range;
        // above it the HUD feels stuck.
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 5_000L
        // Adaptive HUD ping window. Foreground: keep the snappy 5s so a dead HUD is
        // caught in ~5s and the dial loop rediscovers fast. Background (only the
        // ride foreground service up): Samsung / Android background throttling
        // defers the app's socket I/O, so a 5s pong window is easily missed and
        // OkHttp tears down a reconnect before it stabilises (the "0 successful
        // ping/pongs, killed in 5s, rediscover, repeat" loop seen on a Galaxy Z
        // Flip on Android 16). A lenient 15s window lets a briefly-throttled
        // background reconnect survive long enough to pair.
        private const val PING_FOREGROUND_MS = 5_000L
        private const val PING_BACKGROUND_MS = 15_000L
        /** Snapshot pace while no HUD is connected: nothing reads it then. */
        private const val IDLE_SNAPSHOT_MS = 1_000L
        // mDNS resolve timeout. Generous so a flaky multicast path on the
        // hotspot gets a fair chance, but bounded so the rider sees the
        // "no IP / not autodetected" state within a reasonable wait.
        private const val MDNS_RESOLVE_TIMEOUT_MS = 5_000L
        /** Aggressive-retry window after the link is enabled. During this
         *  period the dial loop bottoms out at a 2 s tick so a freshly
         *  reachable HUD is grabbed within a few seconds. After the window
         *  expires the tick relaxes to 5 s. */
        private const val DISCOVERY_SPRINT_MS = 30_000L
        /** TCP probe budget for "is the HUD still reachable after the phone's
         *  WiFi changed". Short: on the same LAN a listener answers in tens of
         *  ms, and the point is to beat the 15 s background ping window. */
        private const val PEER_PROBE_TIMEOUT_MS = 2_500
        /** Minimum gap between "the beacon disagrees with our peer" probes.
         *  Beacons land every 2 s, and one probe every few seconds catches a
         *  moved HUD long before any heartbeat would while still leaving a
         *  healthy link almost entirely alone. */
        private const val BEACON_MISMATCH_PROBE_MS = 5_000L
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

    @Volatile private var loopJob: Job? = null
    @Volatile private var beaconKickJob: Job? = null
    @Volatile private var attentionJob: Job? = null
    /** When the last subnet sweep started; 0 makes the next search sweep.
     *  Reset on link start, network change and the rider starting to look. */
    @Volatile private var lastSweepMs = 0L
    @Volatile private var publishJob: Job? = null
    @Volatile private var ws: HudSocket? = null
    /** "ip:port" of the HUD the live WebSocket is talking to; null when down.
     *  Read by the network-loss probe to test whether the peer is still
     *  reachable after a WiFi transition. */
    private val _peer = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The same address for the settings screen, which shows it on the HUD
     *  app card beside how it was found. */
    val peer: kotlinx.coroutines.flow.StateFlow<String?> get() = _peer
    private var currentPeer: String?
        get() = _peer.value
        set(value) { _peer.value = value }

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

    /** Last time a beacon disagreeing with the live peer sent us to check on it.
     *  Beacons land every 2 s; one probe per [BEACON_MISMATCH_PROBE_MS] is
     *  plenty to catch a moved HUD without probing on a loop. */
    @Volatile private var lastBeaconMismatchProbeMs: Long = 0L

    /** Detects when the phone's OWN home/other Wi-Fi keeps interrupting the HUD
     *  link (single-radio channel-follow): correlates established-link drops
     *  with the phone's Wi-Fi STA transitions. When it fires we set
     *  [HudState.phoneWifiInterfering] so the HUD advises the rider to turn the
     *  phone's Wi-Fi off. */
    private val wifiInterference = WifiInterferenceDetector()

    init {
        // Watch the toggle ourselves so the link starts the moment the
        // rider flips `Enable data link` -- no dependency on WheelService
        // being alive. WheelService still gets to call start/stop too
        // (older code paths); start() and stop() are idempotent so the
        // races are harmless.
        scope.launch {
            var on = false
            settingsRepository.settings.collect { s ->
                backoffMinMs = s.hudBackoffMinMs.toLong()
                backoffMaxMs = s.hudBackoffMaxMs.toLong()
                mdnsTimeoutMs = s.hudMdnsTimeoutMs.toLong()
                discoverySprintMs = s.hudDiscoverySprintMs.toLong()
                udpProbeTimeoutMs = s.hudUdpProbeTimeoutMs.toLong()
                udpBeaconFreshnessMs = s.hudUdpBeaconFreshnessMs.toLong()
                udpPollIntervalMs = s.hudUdpPollIntervalMs.toLong()
                manualHintDelayMs = s.hudManualHintDelayMs.toLong()
                discoveryTotalTimeoutMs = s.hudDiscoveryTotalTimeoutMs.toLong()
                mdnsServiceInfoTimeoutMs = s.hudMdnsServiceInfoTimeoutMs.toLong()
                idleSearchMs = s.hudIdleSearchMs.toLong()
                sweepIntervalMs = s.hudSweepIntervalMs.toLong()
                val want = s.hudServerEnabled ||
                    phone.debug("debug.eucplanet.hud.force") == "true"
                if (want != on) {
                    if (want) start() else stop()
                    on = want
                }
            }
        }
    }
    // Advanced HUD discovery / reconnection timing, mirrored from settings.
    @Volatile private var backoffMinMs: Long = BACKOFF_MIN_MS
    @Volatile private var backoffMaxMs: Long = BACKOFF_MAX_MS
    @Volatile private var mdnsTimeoutMs: Long = MDNS_RESOLVE_TIMEOUT_MS
    @Volatile private var discoverySprintMs: Long = DISCOVERY_SPRINT_MS
    @Volatile private var udpProbeTimeoutMs: Long = 8_000L
    @Volatile private var udpBeaconFreshnessMs: Long = 10_000L
    @Volatile private var udpPollIntervalMs: Long = 200L
    @Volatile private var manualHintDelayMs: Long = 1_500L
    @Volatile private var discoveryTotalTimeoutMs: Long = 15_000L
    @Volatile private var mdnsServiceInfoTimeoutMs: Long = 1_000L
    @Volatile private var idleSearchMs: Long = 120_000L
    @Volatile private var sweepIntervalMs: Long = 60_000L
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
    private fun log(msg: String) = hudLinkNote(TAG, msg)

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
        tileServer.start()
        log("Link enabled, starting discovery")
        if (phone.debug("debug.eucplanet.demo") == "true") {
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
                // Rider-configured HUD report interval; sanitized() guarantees a
                // safe floor so this delay can never spin at 0. With no HUD
                // connected nothing reads the snapshot, so it only stays warm.
                delay(
                    if (ws == null) IDLE_SNAPSHOT_MS
                    else settingsRepository.currentOrLoad().hudReportIntervalMs.toLong()
                )
            }
        }

        // UDP beacon listener only runs when we're discovering (AUTO or
        // BOTH). In FIXED mode the rider explicitly wants no background
        // "find" activity -- only the manual IP they typed should be dialled.
        val s = runCatching { settingsRepository.get() }.getOrNull()
        val mode = s?.hudDiscoveryMode ?: HudDiscoveryMode.AUTO
        if (mode != HudDiscoveryMode.FIXED) {
            udpListener.start()
            log("Discovery on ($mode)")
            // A fresh beacon that lands during a backoff between attempts wakes
            // the dial loop at once instead of waiting out the 2-5 s sleep, so a
            // HUD that comes online mid-session is picked up the moment it
            // announces itself rather than on the next scheduled retry.
            beaconKickJob = scope.launch {
                udpListener.latest.collect { sighting ->
                    if (sighting != null &&
                        System.currentTimeMillis() - sighting.receivedAtMs < udpBeaconFreshnessMs
                    ) {
                        checkPeerAgainstBeacon(sighting)
                        reconnectKick.trySend(Unit)
                    }
                }
            }
        } else {
            val ip = s?.hudIp?.trim().orEmpty()
            val port = s?.hudServerPort?.takeIf { it in 1..65535 }
                ?: HudDiscovery.DEFAULT_PORT
            val target = if (ip.isBlank()) "(no IP set)" else "$ip:$port"
            log("Fixed mode, manual only -> $target")
        }
        network.watch(networkEvents)
        lastSweepMs = 0L
        // A wheel connecting or the app coming on screen means the rider is now
        // waiting for the HUD: end the idle wait and sweep straight away.
        attentionJob = scope.launch {
            combine(wheelRepository.connectionState, phone.foreground) { c, fg ->
                c == ConnectionState.CONNECTED || fg
            }.distinctUntilChanged().drop(1).collect { attending ->
                if (attending) {
                    lastSweepMs = 0L
                    reconnectKick.trySend(Unit)
                }
            }
        }
        loopJob = scope.launch { dialLoop() }
    }

    private suspend fun doStop() {
        Log.i(TAG, "Stopping HUD link")
        log("Link disabled")
        tileServer.stop()
        try { demo.stop() } catch (_: Throwable) {}
        try { ws?.close(1000, "stopping") } catch (_: Throwable) {}
        ws = null
        phone.setMapLinked(false)
        currentPeer = null
        publishJob?.cancel(); publishJob = null
        loopJob?.cancel(); loopJob = null
        beaconKickJob?.cancel(); beaconKickJob = null
        attentionJob?.cancel(); attentionJob = null
        udpListener.stop()
        _connectionSource.value = ConnectionSource.NONE
        try { mdns.release() } catch (_: Throwable) {}
        network.holdRadioAwake(false)
        network.unwatch()
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
     * Track every WiFi network, NOT only the internet-capable ones. Requiring
     * NET_CAPABILITY_INTERNET here was a real bug: when the phone joins the HUD's
     * own AP it joins a network with no internet, so the one network whose loss
     * can strand this link was the one network this callback could never see,
     * and `probePeerAfterNetworkLoss` never fired for the case it was written
     * for. Android's beacon listener already omits the capability for the same reason;
     * these two now agree.
     *
     * This still cannot see a hotspot the PHONE is hosting, which the OS never
     * surfaces as a Network at all. `checkPeerAgainstBeacon` is what covers a
     * stale link in that direction, from the HUD's own announcements.
     *
     * The interference detector still wants only the rider's OWN home / other
     * WiFi (a STA transition on a single radio re-tunes the hotspot and blips
     * the HUD). Internet-capable WiFi is that signal, so it is tracked in
     * the port's own set from the capability callback and fed from there,
     * rather than from the request filter (AndroidHudNetwork).
     */
    private val networkEvents = object : HudNetworkEvents {
        override fun onAvailable() {
            Log.i(TAG, "network onAvailable: kicking dial loop")
            log("Network came up, retrying immediately")
            lastSweepMs = 0L
            reconnectKick.trySend(Unit)
        }
        override fun onOwnWifiJoined() {
            // The phone's own home/other Wi-Fi just (re)joined: on a single
            // radio this re-tunes the hotspot and tends to drop the HUD a
            // few seconds later. Feed the detector so a correlated drop is
            // recognised as channel-follow, not a plain out-of-range loss.
            wifiInterference.onStaTransition(System.currentTimeMillis())
        }
        override fun onLost(wasOwnWifi: Boolean) {
            Log.i(TAG, "network onLost (sta=$wasOwnWifi)")
            if (wasOwnWifi) wifiInterference.onStaTransition(System.currentTimeMillis())
            // If the live WS rode the network that just vanished, it is a
            // zombie that OkHttp only notices at the ping window - 15 s in
            // background, the whole "riding away from WiFi with a frozen
            // HUD" gap. Probe and cut it now instead. Runs for EVERY WiFi
            // network, including the HUD's own: that is the whole point.
            probePeerAfterNetworkLoss()
        }
        override fun onOwnWifiLeaving(msToLive: Int) {
            // DO NOT close the WS here, on either kind of network.
            //
            // For the rider's HOME WiFi (STA): the HUD link rides a separate,
            // no-internet network, so home WiFi going away (rider leaves
            // range) does NOT affect the HUD route, and closing here was pure
            // collateral damage - it tore down a perfectly good link the
            // instant the rider walked out of home range. (Verified against
            // the 2026-06-29 tester log: the link died via ping-timeout, NOT
            // this path, but this close was a latent amplifier.)
            //
            // For the HUD's own network: "losing" is a warning, not a loss.
            // onLost is where we probe and cut, so acting a few seconds early
            // here would just churn a link that may well not go away. The
            // port reports this for the rider's own Wi-Fi only.
            Log.i(TAG, "network onLosing (${msToLive}ms): home WiFi leaving; " +
                "leaving HUD link intact")
            log("Home WiFi dropping in ${msToLive}ms (HUD link unaffected)")
            wifiInterference.onStaTransition(System.currentTimeMillis())
        }
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

    /**
     * A WiFi network we were on just vanished (rider leaving the area, most
     * commonly). If the live WebSocket actually rode that network it is now a
     * zombie: OkHttp only notices at the ping window, which is 15 s while
     * riding (background). Probe the peer directly - a listener on the same
     * LAN answers a TCP connect in tens of ms - and:
     *
     *  - unreachable: cancel the socket NOW. streamUntilClosed returns, the
     *    dial loop re-enters discovery immediately, and the beacon listener
     *    catches the HUD the moment it lands on the next network.
     *  - reachable: do nothing. The HUD rides the phone's own hotspot and the
     *    lost STA network never carried this link (the common riding setup).
     *
     * Probing can't hurt a healthy link, so this is safe on every onLost.
     */
    private fun probePeerAfterNetworkLoss() {
        cutPeerIfUnreachable("WiFi lost")
    }

    /**
     * A discovery beacon that names an address we are NOT connected to is the
     * earliest evidence available that the live socket is half-open, and it
     * arrives every 2 s while the rider waits.
     *
     * This is what the 2026-08-22 capture cost us. The HUD's WiFi re-formed on a
     * new subnet, the phone kept holding a socket to the old address, and the
     * HUD announced its new one from 15:12:47.745 onward. The phone received
     * roughly six of those announcements and acted on none of them, because a
     * beacon could only ever cancel a BACKOFF SLEEP ([delayOrKick]) and never
     * preempt an established socket. The link came back only when the HUD's 12 s
     * heartbeat gave up, after which the re-pair itself took 573 ms.
     *
     * We do not cut on the beacon alone. Two HUD interfaces, or a beacon we
     * misread, must never cost a healthy link, so the disagreement only sends us
     * to CHECK: the peer gets the same TCP probe a network loss triggers, and
     * only an unreachable peer is cut. Probing cannot hurt a live link.
     */
    private fun checkPeerAgainstBeacon(sighting: HudBeaconSighting) {
        val peer = currentPeer ?: return
        if (!StaleLink.beaconContradictsPeer(peer, sighting.ip, sighting.port)) return
        val now = System.currentTimeMillis()
        if (now - lastBeaconMismatchProbeMs < BEACON_MISMATCH_PROBE_MS) return
        lastBeaconMismatchProbeMs = now
        log("HUD announces ${sighting.ip}:${sighting.port} but we hold $peer - checking it")
        cutPeerIfUnreachable("the HUD moved to ${sighting.ip}:${sighting.port}")
    }

    /** Probe [currentPeer] and cut the socket if nothing answers. [why] is the
     *  reason that sent us looking, quoted back into the rider's trace. */
    private fun cutPeerIfUnreachable(why: String) {
        val peer = currentPeer ?: return
        scope.launch {
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "").toIntOrNull() ?: HudDiscovery.DEFAULT_PORT
            val reachable = runCatching {
                network.tryTcp(host, port, PEER_PROBE_TIMEOUT_MS)
            }.getOrDefault(false)
            if (!reachable && currentPeer == peer) {
                log("$why, and $peer stopped answering - reconnecting now")
                // cancel(), not close(): close() queues a frame on a transport
                // that is already gone and still waits out the ping window.
                // cancel() fails the socket immediately (onFailure fires).
                try { ws?.cancel() } catch (_: Throwable) {}
                reconnectKick.trySend(Unit)
            }
        }
    }

    /** Keep the radio out of power-save while the HUD link needs it.
     *  Idempotent and best-effort, see [HudNetwork.holdRadioAwake]. */
    private fun acquireWifiPerfLock() = network.holdRadioAwake(true)

    private fun releaseWifiPerfLock() = network.holdRadioAwake(false)

    /**
     * Outer loop: discover-or-read the HUD address, open a WebSocket, pump
     * state until it dies, back off, retry.
     *
     * In AUTO (default) and BOTH modes every channel runs at once and the
     * first answer wins, see [resolvePeer]:
     *
     *   - UDP beacon sighting (cheap, the most reliable channel because it
     *     works on hotspots that block multicast)
     *   - mDNS browse on `_eucplanet._tcp.local.`
     *   - /24 subnet probe of the phone's own networks, rate limited by
     *     sweepIntervalMs
     *   - Manual `hudIp` from settings, BOTH mode only and after a short
     *     grace delay (a last-known hint, not the only truth). AUTO skips
     *     this so a stale IP the rider cannot see (the field is hidden) can
     *     never win the race.
     *
     * FIXED mode skips discovery entirely and uses only the manual IP - an
     * escape hatch for the rare environment where every auto channel misleads
     * us. See [HudDiscoveryMode].
     *
     * Each attempt's source is published on [connectionSource] so the
     * settings screen can show "Connected via: UDP beacon" and the rider
     * has a one-glance read on which channel did the work.
     */
    private suspend fun dialLoop() {
        var attempt = 0
        val sprintStartedAtMs = System.currentTimeMillis()
        while (true) {
            // Riding, the radio stays out of power-save the whole time, as it
            // always did: a HUD lost mid-ride has to be found again by
            // broadcast and multicast, which a dozing radio drops. Only a phone
            // with no wheel lets it doze while no HUD answers.
            val riding = wheelRepository.connectionState.value == ConnectionState.CONNECTED
            if (riding) acquireWifiPerfLock()
            val s = runCatching { settingsRepository.currentOrLoad() }.getOrNull()
            val override = phone.debug("debug.eucplanet.hud.peer")?.takeIf { it.isNotBlank() }
            val mode = s?.hudDiscoveryMode ?: HudDiscoveryMode.AUTO
            val manualIp = s?.hudIp?.trim().orEmpty()
            val manualPort = s?.hudServerPort?.takeIf { it in 1..65535 }
                ?: HudDiscovery.DEFAULT_PORT

            val (peer, source) = when {
                override != null -> override to ConnectionSource.DEBUG_OVERRIDE
                mode == HudDiscoveryMode.FIXED -> resolveManualOnly(s, manualIp, manualPort)
                // BOTH adds the saved IP as a fallback hint; AUTO never does.
                else -> resolvePeer(
                    manualIp, manualPort,
                    useManualHint = mode == HudDiscoveryMode.BOTH,
                )
            }

            if (peer == null) {
                _connectionSource.value = ConnectionSource.NONE
                // No HUD on this network and no ride: let the Wi-Fi radio
                // power-save. It used to be held awake all day whenever the
                // HUD was enabled.
                if (!riding) releaseWifiPerfLock()
                // Sprint mode: aggressively retry every 2 s for the first
                // 30 s after the dial loop kicks off so the rider gets a
                // fast connection on a healthy network. After the sprint
                // window we relax to a 5 s tick to be kinder to the radio
                // when the network is genuinely broken. A NetworkCallback
                // kick (phone hopped to a different WiFi, came back from
                // airplane mode) cancels whichever delay is pending so we
                // retry immediately instead of waiting out the tick.
                //
                // With nobody waiting (no wheel, app off screen) the wait is
                // idleSearchMs instead (HudSearchPacing). The beacon listener,
                // a network change, a wheel connecting or the app opening all
                // still end it at once.
                val attending = phone.foreground.value ||
                    wheelRepository.connectionState.value == ConnectionState.CONNECTED
                delayOrKick(
                    HudSearchPacing.waitMs(
                        attending,
                        sinceStartMs = System.currentTimeMillis() - sprintStartedAtMs,
                        sprintMs = discoverySprintMs,
                        idleMs = idleSearchMs,
                    )
                )
                continue
            }
            _connectionSource.value = source
            // Keep the radio out of power-save while a HUD is dialled and
            // streamed. Re-asserted on every dial: the OS can reclaim it, or it
            // can lapse on a network hop. Idempotent.
            acquireWifiPerfLock()
            val ok = streamUntilClosed(peer)
            if (!ok) {
                // Nothing answered at that address (a fixed IP with the HUD
                // off): same rule as finding no HUD at all.
                if (!riding) releaseWifiPerfLock()
                delayOrKick(backoff(attempt++))
            } else {
                attempt = 0
            }
        }
    }

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
        if (manualIp.isNotBlank() &&
            com.eried.eucplanet.hud.protocol.HudDiscovery.isValidIpv4(manualIp)
        ) {
            // Always honour the typed IP. Port falls back to default 28080
            // when the rider left it blank / invalid.
            return "$manualIp:$manualPort" to ConnectionSource.MANUAL
        }
        if (manualIp.isNotBlank()) {
            // Auto-find is off, so there is no other channel to fall back to.
            // Say which value is wrong rather than looping on a DNS failure.
            log("Manual IP \"$manualIp\" is not a complete address - fix it in Settings")
            return null to ConnectionSource.NONE
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
     *
     * A saved manual IP is NOT one of them. Auto-find hides the field that
     * holds it, so using it here meant dialling an address the rider could not
     * see: one tester's stale entry beat his HUD's own beacon twice in a row.
     * Auto-find off is how you ask for that address ([resolveManualOnly]).
     *
     * This is the "try harder" the rider asked for: instead of stepping
     * through sequentially and waiting for each to give up before the
     * next one starts, every channel runs at once. Bottoms out at the
     * SLOWEST channel only when none of them find a HUD.
     */
    private suspend fun resolvePeer(
        manualIp: String,
        manualPort: Int,
        useManualHint: Boolean,
    ): Pair<String?, ConnectionSource> = kotlinx.coroutines.coroutineScope {
        // Which networks the phone is on decides what every channel below can
        // possibly reach, and it is the first thing to check when all of them
        // come up empty. Logged per search because it changes: a hotspot coming
        // up, home WiFi dropping as the rider leaves.
        val phoneNets = runCatching { network.candidateIpv4Cidrs() }.getOrDefault(emptyList())
        log(
            if (phoneNets.isEmpty()) "Phone networks: none (no WiFi, no hotspot)"
            else "Phone networks: $phoneNets"
        )
        log("Searching (all channels in parallel)…")
        val results = kotlinx.coroutines.channels.Channel<Pair<String, ConnectionSource>>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )

        val udpJob = scope.launch {
            // Check the listener's cache repeatedly so a freshly-arrived beacon
            // shows up within ~200 ms. Listen for the WHOLE attempt, not a short
            // sub-window: a beacon that lands late in the search still wins
            // immediately instead of being missed after udpProbeTimeoutMs while
            // the attempt idled on until the total timeout. That gap cost a real
            // HUD ~10 s of dead air - it beaconed a few seconds after the old
            // 8 s window had already closed, and only the next backoff dialled
            // it. Bounded by discoveryTotalTimeoutMs, which also cancels this
            // job the instant any channel wins.
            val until = System.currentTimeMillis() + maxOf(udpProbeTimeoutMs, discoveryTotalTimeoutMs)
            // Log the "none yet" diagnostic once, after the short probe window,
            // then keep listening. "Never heard one" points off the phone (HUD
            // on another network or out of range); "heard some, none lately"
            // points at the link going away under us, a different problem.
            val diagAt = System.currentTimeMillis() + udpProbeTimeoutMs
            var diagnosed = false
            while (System.currentTimeMillis() < until) {
                val s = udpListener.latest.value
                if (s != null && System.currentTimeMillis() - s.receivedAtMs < udpBeaconFreshnessMs) {
                    log("UDP beacon: ${s.ip}:${s.port}")
                    results.send("${s.ip}:${s.port}" to ConnectionSource.UDP_BEACON)
                    return@launch
                }
                if (!diagnosed && System.currentTimeMillis() >= diagAt) {
                    diagnosed = true
                    val heard = udpListener.totalReceived
                    val lastRx = udpListener.lastReceiveAtMs
                    val bindErr = udpListener.lastBindError
                    val since = if (lastRx == 0L) "never"
                        else "${(System.currentTimeMillis() - lastRx) / 1000}s ago"
                    log(
                        "UDP beacon: none yet, still listening " +
                            "(listener: $heard total, last $since" +
                            (if (bindErr.isNotBlank()) ", bind error: $bindErr" else "") + ")"
                    )
                }
                kotlinx.coroutines.delay(udpPollIntervalMs)
            }
        }

        val mdnsJob = scope.launch {
            log("mDNS: browsing _eucplanet._tcp.local…")
            // Elapsed, not the configured timeout. The old line always claimed
            // the full timeout, so a cancelled browse (another channel won
            // first) read as "the network swallowed our query" - which sent a
            // real investigation looking for multicast filtering that was
            // never there. A cancelled job now logs nothing at all.
            val startedAt = System.currentTimeMillis()
            val v = resolveViaMdns()
            val tookMs = System.currentTimeMillis() - startedAt
            if (v != null) {
                log("mDNS: found $v")
                results.send(v to ConnectionSource.MDNS)
            } else {
                log("mDNS: no answer in ${"%.1f".format(tookMs / 1000f)}s")
            }
        }

        // The sweep tries every address on the network, by far the costliest
        // channel, so it runs at most once per sweepIntervalMs. A new network
        // or the rider starting to look resets the clock (lastSweepMs = 0).
        // Beacon and mDNS still run on every search.
        val now = System.currentTimeMillis()
        val probeJob = if (!HudSearchPacing.sweepDue(now, lastSweepMs, sweepIntervalMs)) {
            log("Subnet probe: skipped, last sweep ${(now - lastSweepMs) / 1000}s ago")
            null
        } else scope.launch {
            lastSweepMs = System.currentTimeMillis()
            // Last resort, and gated: this only runs if the faster mDNS / UDP
            // paths did not already find the HUD (a win cancels this job). It
            // starts immediately - no stagger - because the scan is bounded
            // (HudSubnetProbe MAX_CONCURRENT) and no longer floods the radio the
            // way the old unbounded burst did, so it does not need to hold off.
            log("Subnet probe: scanning local subnets…")
            val ip = subnetProbe.probe(manualPort)
            if (ip != null) {
                log("Subnet probe: $ip:$manualPort answered")
                results.send("$ip:$manualPort" to ConnectionSource.SUBNET_PROBE)
            } else {
                log("Subnet probe: nothing answered")
            }
        }

        // BOTH only: AUTO passes useManualHint = false so a saved IP the
        // rider cannot see (its field is hidden) can never enter the race.
        val manualJob = if (useManualHint && manualIp.isNotBlank()) {
            scope.launch {
                // Small grace period so a healthy UDP / mDNS hit wins the race
                // before we fall back to a possibly-stale manual IP.
                kotlinx.coroutines.delay(manualHintDelayMs)
                log("Manual hint: $manualIp:$manualPort")
                results.send("$manualIp:$manualPort" to ConnectionSource.MANUAL)
            }
        } else {
            if (manualIp.isNotBlank()) log("Auto mode, ignoring the saved IP $manualIp")
            null
        }

        val allJobs = listOfNotNull(udpJob, mdnsJob, probeJob, manualJob)
        try {
            val winner = kotlinx.coroutines.withTimeoutOrNull(discoveryTotalTimeoutMs) {
                results.receive()
            }
            if (winner != null) winner else null to ConnectionSource.NONE
        } finally {
            // Cancel, then wait for the channels to actually stop - but NOT for
            // the JmDNS close, which is detached in resolveViaMdns. Without the
            // wait, a fast-failing cycle (a stale beacon inside its freshness
            // window wins instantly, the dial is refused, backoff 1 s) starts
            // the next cycle while this one is still unwinding, and JmDNS.create
            // runs again on a socket the previous instance has not let go of.
            //
            // NonCancellable because this finally also runs when the caller was
            // cancelled (link switched off), where a plain join would return
            // immediately and skip the wait.
            allJobs.forEach { it.cancel() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.joinAll(*allJobs.toTypedArray())
            }
        }
    }

    /** Resolve `_eucplanet._tcp.local.` on whatever subnet we have. Returns
     *  `host:port` of the first matching HUD with a compatible protocol
     *  version, or null if nothing answered within [mdnsTimeoutMs]. */
    private suspend fun resolveViaMdns(): String? =
        mdns.resolve(mdnsTimeoutMs, mdnsServiceInfoTimeoutMs)

    /** Open one WebSocket and pump frames until it closes. Returns true on
     *  clean close, false on transport error (which gates backoff). */
    private suspend fun streamUntilClosed(peer: String): Boolean {
        val foreground = phone.uiForeground()
        val pingMs = if (foreground) PING_FOREGROUND_MS else PING_BACKGROUND_MS
        log("Dial ws://$peer/state (ping ${pingMs / 1000}s, ${if (foreground) "foreground" else "background"})")
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val listener = object : HudSocketEvents {
            private var sendJob: Job? = null
            // One per connection: a new link starts by sending the overlay.
            private val overlay = com.eried.eucplanet.hud.protocol.OverlaySender()
            /** True once this socket actually opened, so onFailure can tell an
             *  ESTABLISHED-link drop (a real interruption worth correlating with
             *  Wi-Fi) from a plain "couldn't find/connect to the HUD" dial miss. */
            @Volatile private var wasOpen = false
            override fun onOpen(socket: HudSocket) {
                val webSocket = socket
                Log.i(TAG, "HUD link open: $peer")
                log("Connected to $peer ✓")
                wasOpen = true
                ws = webSocket
                currentPeer = peer
                // Push a frame on the rider's HUD report interval off the
                // snapshot buffer. We don't dedupe: even when no field changed, the
                // timestamp bump in [snapshot] keeps the HUD's last-frame
                // freshness signal live.
                sendJob = scope.launch {
                    // The buffer only refreshes once a second while no HUD is
                    // linked, so the first frame is taken fresh.
                    var first = true
                    while (true) {
                        val frame = if (first) runCatching { snapshot() }.getOrDefault(latest) else latest
                        first = false
                        // The HUD draws its map screens from this phone's
                        // position, so keep GPS on while one is in the carousel.
                        if (isActive) phone.setMapLinked(HudScreens.hasMap(frame.enabledHudScreens))
                        try {
                            // The custom overlay rides along when it changed,
                            // not in every frame, for a HUD that understands
                            // "same as before". See OverlaySender.
                            val out = overlay.stamp(
                                frame, commandSink.hudProtocolMinor,
                                (com.eried.eucplanet.platform.System.nanoTime() / 1_000_000L),
                            )
                            if (!webSocket.send(json.encodeToString(out))) {
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
                        // Rider-configured HUD report interval; sanitized() guarantees a
                // safe floor so this delay can never spin at 0.
                delay(settingsRepository.currentOrLoad().hudReportIntervalMs.toLong())
                    }
                }
            }
            override fun onMessage(text: String) {
                try {
                    val cmd = json.decodeFromString<HudCommand>(text)
                    commandSink.dispatch(cmd)
                } catch (t: Throwable) {
                    Log.w(TAG, "bad command: ${t.message}: $text")
                }
            }
            override fun onClosing(socket: HudSocket, code: Int, reason: String) {
                Log.i(TAG, "HUD link closing: $code $reason")
                // The HUD hanging up was the ONE way this link could end without
                // leaving a single line in the rider's capture, which is exactly
                // how the 2026-08-22 report arrived: eleven silent seconds, then
                // a search out of nowhere. It is the normal end of a half-open
                // socket (the HUD's heartbeat gives up and closes), so it is the
                // line most worth having.
                log("HUD closed the link: $code ${reason.ifBlank { "(no reason given)" }}")
                socket.close(1000, null)
            }
            override fun onClosed() {
                sendJob?.cancel()
                phone.setMapLinked(false)
                ws = null
                currentPeer = null
                commandSink.onHudDisconnected()
                phone.onLinkDown()
                done.complete(true)
            }
            override fun onFailure(error: Throwable, httpCode: Int?) {
                val t = error
                Log.w(TAG, "HUD link failure: ${t.message} ($httpCode)")
                log("Not found: ${t.message ?: t::class.simpleName}")
                // Only an ESTABLISHED link dropping is a candidate channel-follow
                // event; a failed dial (never opened) is just discovery missing.
                if (wasOpen) {
                    val correlated = wifiInterference.onHudDrop(System.currentTimeMillis())
                    if (correlated) log("Drop correlated with a phone Wi-Fi change")
                }
                sendJob?.cancel()
                phone.setMapLinked(false)
                ws = null
                currentPeer = null
                commandSink.onHudDisconnected()
                phone.onLinkDown()
                done.complete(false)
            }
        }
        // Per-connection ping tolerance from the foreground/background state above.
        sockets.open("ws://$peer${HudDiscovery.PATH_STATE}", pingMs, listener)
        return done.await()
    }

    private fun backoff(attempt: Int): Long {
        val expanded = backoffMinMs shl attempt.coerceAtMost(3)
        return expanded.coerceAtMost(backoffMaxMs)
    }

    /** The map heading, kept through a stop so the HUD map does not swing
     *  back to north-up at every halt. */
    private val heldHeading = com.eried.eucplanet.hud.protocol.HeldHeading()

    // The last route piece cut for the HUD map and what it was cut from. The
    // state goes out several times a second and a fix arrives once, so most
    // frames reuse the string.
    private var routeWindowLeg: com.eried.eucplanet.data.model.NavRoute? = null
    private var routeWindowLat = Double.NaN
    private var routeWindowLng = Double.NaN
    private var routeWindowFlat = DoubleArray(0)
    private var routeWindowEncoded = ""

    /** The route line around ([lat], [lng]) for the HUD map, "" without one. */
    private fun routeWindow(lat: Double, lng: Double): String {
        val leg = navigationEngine.activeLeg.value
        if (leg == null || leg.geometry.size < 2) return ""
        if (leg !== routeWindowLeg) {
            routeWindowLeg = leg
            routeWindowFlat = DoubleArray(leg.geometry.size * 2).also { flat ->
                leg.geometry.forEachIndexed { i, p ->
                    flat[2 * i] = p.lat
                    flat[2 * i + 1] = p.lng
                }
            }
            routeWindowLat = Double.NaN
        }
        if (lat != routeWindowLat || lng != routeWindowLng) {
            routeWindowLat = lat
            routeWindowLng = lng
            routeWindowEncoded = com.eried.eucplanet.hud.protocol.RouteWindow
                .encodeAround(routeWindowFlat, lat, lng)
        }
        return routeWindowEncoded
    }

    private suspend fun snapshot(): HudState {
        // Let the Wi-Fi-interference advisory decay once the link has been
        // stable for a while (this runs every publish tick, ~5 Hz).
        wifiInterference.onStableTick(System.currentTimeMillis())
        val s = settingsRepository.currentOrLoad()
        val wd = wheelRepository.wheelData.value
        val state = wheelRepository.connectionState.value
        val nav = navigationEngine.navState.value
        val location = phone.location()
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

        // The HUD shows the next turn for the whole route. The phone's popup
        // hides after a few seconds and is gone with the phone in a pocket,
        // so tying the HUD to it left Map + Nav and Nav without turns.
        val navShow = nav.active

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
        val overrideMajor = phone.debug("debug.eucplanet.proto.major")
            ?.toIntOrNull() ?: HudState.PROTOCOL_MAJOR
        val overrideMinor = phone.debug("debug.eucplanet.proto.minor")
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
            phaseCurrent = wd.phaseCurrent,
            motorPower = wd.motorPower,
            gForce = wd.gForce,
            lightOn = wd.lightOn,
            gaugeMaxKmh = gaugeMax,
            gaugeOrangeThresholdPct = s.gaugeOrangeThresholdPct,
            gaugeRedThresholdPct = s.gaugeRedThresholdPct,
            showGaugeColorBand = if (d != null) true else s.showGaugeColorBand,
            unitSpeed = com.eried.eucplanet.util.Units.effectiveSpeedUnit(s),
            unitDistance = com.eried.eucplanet.util.Units.effectiveDistanceUnit(s),
            unitTemp = com.eried.eucplanet.util.Units.effectiveTempUnit(s),
            unitPressure = com.eried.eucplanet.util.Units.effectivePressureUnit(s),
            unitAltitude = com.eried.eucplanet.util.Units.effectiveAltitudeUnit(s),
            batteryEnvelope = wd.batteryEnvelope,
            accentArgb = resolveAccentArgb(),
            latitude = (location?.latitude ?: 0.0) + (d?.dLat ?: 0.0),
            longitude = (location?.longitude ?: 0.0) + (d?.dLng ?: 0.0),
            gpsSpeedKmh = gpsSpeedPair?.first ?: Float.NaN,
            gpsSource = gpsSpeedPair?.second ?: "",
            // Phone and external GPS speeds independently, so the HUD can show
            // both overlay metrics at once (the primary gpsSpeedKmh above only
            // carries whichever source won the priority pick).
            phoneGpsSpeedKmh = if (location?.hasSpeed() == true) location.speed * 3.6f else Float.NaN,
            externalGpsSpeedKmh = if (externalFresh) external!!.speedKmh else Float.NaN,
            gpsHasFix = location != null,
            gpsHeadingDeg = heldHeading.update(
                bearingDeg = if (location?.hasBearing() == true) location.bearing else null,
                speedMs = if (location?.hasSpeed() == true) location.speed else null,
            ),
            gpsAltitudeM = if (location?.hasAltitude() == true) location.altitude.toFloat()
                else Float.NaN,
            externalGpsBatteryPercent = if (externalFresh) (external!!.batteryPercent ?: -1) else -1,
            tripMeterKm = tripMeterRepository.distanceKm,
            whConsumed = wd.whConsumed,
            whPerKm = wd.whPerKmRecent,
            rangeKm = wd.rangeKmEstimate,
            tirePressureKpa = wd.tirePressureKpa,
            wheelRollDeg = wd.rollAngle,
            wheelPitchDeg = wd.pitchAngle,
            customOverlayJson = s.hudCustomOverlayJson,
            // Carousel order = the rider's preferred row order, filtered
            // to keep only the enabled screens. Both fields are
            // independent in storage so toggling a row off doesn't move
            // it in the Personalize list. Never empty: the HUD treats an
            // empty list as "no wire frame received yet" and shows a
            // waiting splash.
            enabledHudScreens = HudScreens.carousel(s.hudScreensOrder, s.hudScreensEnabled),
            hudMapStyle = s.hudDisplay.mapStyle,
            hudMapContrastPct = s.hudDisplay.mapContrastPct,
            hudMapBrightnessPct = s.hudDisplay.mapBrightnessPct,
            autoStart = s.hudDisplay.autoStart,
            navActive = d?.navActive ?: navShow,
            navArrowAngleDeg = d?.navAngleDeg ?: nav.arrowAngleDeg(),
            navPrimary = d?.navPrimary ?: nav.primaryText,
            navDistance = d?.navDistance ?: nav.distanceText,
            navArrived = d?.navArrived ?: nav.arrived,
            // For the whole route, not only while the turn popup is up:
            // the rider wants the arrival time between turns too.
            navEta = if (d == null && nav.active && !nav.arrived) {
                com.eried.eucplanet.nav.NavFormat.etaLine(
                    phone::text, phone::shortTime, nav.etaEpochMs, nav.remainingM,
                    imperial = com.eried.eucplanet.util.Units.effectiveDistanceUnit(s) == "mi"
                )
            } else "",
            navRoute = if (d == null && nav.active && !nav.arrived && location != null) {
                routeWindow(location.latitude, location.longitude)
            } else "",
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
            timestampMs = System.currentTimeMillis(),
            phoneWifiInterfering = wifiInterference.advisoryActive
        )
    }

    /** Resolve a configured joystick-slot action [key] to its human-readable
     *  catalog label. "" / "NONE" (the unset sentinel) and any key the catalog
     *  doesn't know map to "" so the HUD draws that direction as unset. */
    private fun joystickLabel(key: String): String {
        if (key.isBlank() || key == "NONE") return ""
        val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key) ?: return ""
        return phone.text(spec.labelRes)
    }

    private fun resolveAccentArgb(): String =
        com.eried.eucplanet.ui.theme.ThemeAccent.primaryArgb(themeController.activeColors.value)
}
