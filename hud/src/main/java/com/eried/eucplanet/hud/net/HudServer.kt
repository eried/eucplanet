package com.eried.eucplanet.hud.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDebug
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * HUD-side WebSocket server. The phone is the dialer in this architecture:
 * it opens an outbound socket to the HUD's IP and pushes [HudState] frames as
 * the wheel telemetry changes. The HUD decodes them, drives its UI off the
 * resulting StateFlow, and sends [HudCommand] frames back on the same socket
 * (headlight toggle, stop-navigation, etc).
 *
 * Why HUD-as-server and not phone-as-server (the previous build): phone
 * hotspots routinely drop multicast and / or enforce client isolation
 * between connected devices, which kills any "phone advertises a service,
 * HUD discovers it" path. With the dialer on the phone side, the rider
 * reads the HUD's IP off the HUD screen and types it into the phone app --
 * a flow that survives any hotspot policy short of a full block on
 * outbound TCP from the phone.
 *
 * Owned by [com.eried.eucplanet.hud.HudActivity]: created in onCreate,
 * stopped in onDestroy. No foreground service: the HUD app is always the
 * foreground app on the HUD device.
 */
class HudServer(private val context: Context) {

    companion object {
        private const val TAG = "HudServer"
        private const val MULTICAST_LOCK_TAG = "eucplanet-hud-advertise"
        /** Server bind port. Matches [HudDiscovery.DEFAULT_PORT] so testers
         *  don't need to change anything in the phone settings. */
        private val PORT = HudDiscovery.DEFAULT_PORT
        /** How often the self-heal watchdog ticks (ms). 10 s so a wedged
         *  listener or a stalled mDNS/beacon is caught and restarted within
         *  ~20-30 s -- snappy enough that the rider rarely sees it, but not
         *  so tight it churns. The CPU wake lock (see [wakeLock]) keeps this
         *  loop running even if the device tries to suspend. */
        private const val WATCHDOG_INTERVAL_MS: Long = 10_000L
        /** Consecutive failed localhost probes before forcing a server
         *  restart. Two misses (~20-30 s) rides out a transient localhost
         *  blip without acting on it. */
        private const val WATCHDOG_FAIL_THRESHOLD: Int = 2
        /** Per-attempt timeout for the watchdog's localhost TCP probe (ms). */
        private const val TCP_PROBE_TIMEOUT_MS: Int = 1_500
        /** Wake-lock tag; shows up in `dumpsys power` for debugging. */
        private const val WAKE_LOCK_TAG = "eucplanet-hud:server"
    }

    /** Connection state surfaced to the UI status banner. */
    enum class Status { LISTENING, CONNECTED }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Match the phone's encoder: HudState carries Float.NaN for "no
        // GPS fix" / "no bearing"; decoder needs to tolerate that.
        allowSpecialFloatingPointValues = true
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught", t) }
    )
    private val lifecycleLock = Mutex()

    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status.LISTENING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Classification of the phone's reported protocol version against
     *  ours. Recomputed on every accepted frame. Surfaces on the
     *  disconnect splash / Map screen so the rider sees whether the
     *  HUD APK is the one to update or whether the phone app is. */
    private val _versionCompat = MutableStateFlow(com.eried.eucplanet.hud.protocol.VersionCompat.EXACT)
    val versionCompat: StateFlow<com.eried.eucplanet.hud.protocol.VersionCompat> = _versionCompat.asStateFlow()

    /** Local IPv4 we're bound to, displayed on the HUD banner so the rider
     *  knows what to type into the phone app. Null until we resolve it. */
    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    /** Address of the phone currently connected to us, e.g. "192.168.43.5".
     *  Null when no client is attached. */
    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    /** Commands the UI wants to send back to the phone. Buffered so a button
     *  press during a disconnect window isn't silently dropped -- the phone
     *  will see it when it reconnects. Replay = 0 because we don't want to
     *  re-fire commands on every new connection, only deliver pending ones. */
    private val outboundCommands = Channel<HudCommand>(capacity = Channel.UNLIMITED)

    private var server: io.ktor.server.engine.ApplicationEngine? = null
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    /** CPU wake lock held while the link is up. The HUD keeps the screen on
     *  (FLAG_KEEP_SCREEN_ON) which usually keeps the CPU running, but some
     *  aftermarket HUDs power the panel independently or background the app;
     *  a partial wake lock guarantees the Ktor server, beacon and watchdog
     *  coroutines keep ticking so the link can't silently freeze the way a
     *  tester saw it die after ~2 min. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    /** WiFi performance lock. The CPU wake lock above keeps our coroutines
     *  ticking, but on many devices the WiFi RADIO still drops into DTIM
     *  power-save (or loses the association) once traffic goes idle -- so the
     *  phone's pings get no pong and a fresh dial can't even connect, while our
     *  localhost watchdog still passes (the Ktor server is up, just unreachable
     *  over the air). That is the "link died after ~2 min, only a reboot fixed
     *  it" failure. A high-perf / low-latency WiFi lock keeps the radio awake. */
    private var wifiLock: WifiManager.WifiLock? = null

    // --- Diagnostics (surfaced on the HUD stats card + logcat) so the next
    // "link died, had to reboot" report carries the cause, not just the
    // symptom. ---
    /** How many times the watchdog has force-restarted the server this run. */
    @Volatile var watchdogRestarts: Int = 0; private set
    /** Why the last phone connection ended (clean close / timeout / exception). */
    @Volatile var lastEndReason: String = ""; private set
    /** Wall-clock of the last disconnect, 0 if still on the first connection. */
    @Volatile var lastDisconnectMs: Long = 0L; private set
    /** Self-heal watchdog. Polls the local listener and the current IPv4 every
     *  [WATCHDOG_INTERVAL_MS] so a hotspot IP-renew or a silently-dead Ktor
     *  socket gets fixed without the rider having to reboot the HUD. */
    private var watchdogJob: Job? = null
    /** Count of consecutive watchdog ticks where the local TCP probe failed.
     *  We require [WATCHDOG_FAIL_THRESHOLD] in a row before a full restart so
     *  a transient localhost blip doesn't churn the server. */
    @Volatile private var watchdogFailStreak: Int = 0
    /** Monotonic connection counter so a stale, slowly-timing-out WebSocket
     *  handler doesn't clobber the status/peer of a newer connection that
     *  arrived during a fast reconnect. */
    private val connSeq = java.util.concurrent.atomic.AtomicInteger(0)
    // Always-on UDP broadcast beacon so the phone can find us on networks
    // where mDNS multicast is blocked (most phone hotspots, every carrier
    // mobile hotspot). Runs in parallel with the mDNS advertise -- whichever
    // discovery channel survives the network gets used.
    private val udpBeacon = HudUdpBeacon()
    // Symmetric discovery: HUD also actively shouts a probe so on hostile
    // networks where the phone's RX of broadcast is blocked, the phone's
    // OWN broadcast (if any) lands via the same socket the HUD opened.
    val phoneFinder = HudPhoneFinder()

    fun start() {
        scope.launch { lifecycleLock.withLock { doStart() } }
    }

    fun stop() {
        scope.launch { lifecycleLock.withLock { doStop() } }
    }

    /** Queue a command to be sent to the connected phone. Returns
     *  immediately; delivery happens on the next outbound frame. */
    fun sendCommand(cmd: HudCommand) {
        outboundCommands.trySend(cmd)
    }

    private suspend fun doStart() {
        if (server != null) return
        acquireWakeLock()
        acquireWifiLock()
        _localIp.value = pickLocalIp()
        val s = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
                // Heartbeat: phone or HUD network can go away silently. On the
                // local hotspot link (sub-10ms RTT) we can afford an aggressive
                // beat: ping every 5s and declare the peer dead after 12s of
                // silence. This frees [_peer]/[_status] and unblocks the
                // `incoming` loop within ~12s of the phone vanishing instead of
                // 30s -- so a fresh dial from the phone is accepted promptly and
                // the rider isn't stuck on a stale "connected" HUD.
                // Millis form because the Duration property accessor names
                // moved across Ktor 2.x; millis is stable.
                pingPeriodMillis = 5_000L
                timeoutMillis = 12_000L
            }
            routing {
                webSocket(HudDiscovery.PATH_STATE) {
                    val remote = call.request.local.remoteHost
                    // Token for THIS connection. With fast heartbeats a phone
                    // can redial while a previous handler is still unwinding its
                    // timeout; we must not let the stale handler's finally clear
                    // the status/peer that the newer connection already set.
                    val myConn = connSeq.incrementAndGet()
                    Log.i(TAG, "phone connected: $remote (#$myConn)")
                    _peer.value = remote
                    _status.value = Status.CONNECTED
                    // Diagnostic: remember the phone IP across reconnects
                    // so the HUD-side stats card can show "last phone: X"
                    // even after the link drops.
                    phoneFinder.notePhone(remote)

                    // Greet the phone with our version. The phone uses
                    // this to decide whether to show "update HUD" or
                    // "update Phone" hints. Older HUD APKs sent a Pair
                    // without protocol fields; default 0/0 there means
                    // "treat as the pre-split baseline 1.0".
                    //
                    // Debug-only overrides let a tester drive the
                    // version-mismatch UI without rebuilding a
                    // deliberately mismatched APK. See HudDebug.
                    val sendMajor = HudDebug.read("debug.eucplanet.proto.major")
                        ?.toIntOrNull() ?: HudState.PROTOCOL_MAJOR
                    val sendMinor = HudDebug.read("debug.eucplanet.proto.minor")
                        ?.toIntOrNull() ?: HudState.PROTOCOL_MINOR
                    try {
                        send(json.encodeToString<HudCommand>(
                            HudCommand.Pair(
                                hudId = "motoeye-hud",
                                hudVersion = com.eried.eucplanet.hud.BuildConfig.VERSION_NAME,
                                hudProtocolMajor = sendMajor,
                                hudProtocolMinor = sendMinor
                            )
                        ))
                    } catch (t: Throwable) {
                        Log.w(TAG, "pair greeting failed: ${t.message}")
                    }

                    // Pump outbound commands. Launched in this socket's
                    // scope so the loop ends when the socket dies, no need
                    // to track and cancel manually.
                    val sender = launch {
                        for (cmd in outboundCommands) {
                            try {
                                send(json.encodeToString<HudCommand>(cmd))
                            } catch (t: Throwable) {
                                // Channel will redeliver via the buffer on
                                // next connection; just log and bail.
                                Log.w(TAG, "send failed: ${t.message}")
                                break
                            }
                        }
                    }

                    // Capture WHY the for-loop ended so the next time the
                    // rider reports a 2-min drop we know whether it was a
                    // clean phone close, a heartbeat timeout, or an
                    // exception coming back up. Without this the disconnect
                    // signal is "phone disconnected" and nothing more.
                    var endReason: String = "frames exhausted"
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                try {
                                    val s = json.decodeFromString<HudState>(frame.readText())
                                    // Major MUST be <= ours: a higher
                                    // major may include fields we can't
                                    // parse safely, so drop the frame
                                    // and let the UI surface the
                                    // "update HUD" banner instead of
                                    // showing stale values.
                                    val compat = com.eried.eucplanet.hud.protocol.VersionCompat
                                        .classify(s.protocolMajor, s.protocolMinor)
                                    _versionCompat.value = compat
                                    if (!compat.isBlocking) {
                                        _state.value = s
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "decode failed: ${t.message}")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        endReason = "exception: ${t::class.simpleName} ${t.message}"
                    } finally {
                        sender.cancel()
                        Log.i(TAG, "phone disconnected: $remote (#$myConn, $endReason)")
                        lastEndReason = endReason
                        lastDisconnectMs = System.currentTimeMillis()
                        // Only surrender the connection state if no newer
                        // connection has taken over since we opened. Otherwise
                        // a slow-timing-out stale handler would wrongly flip the
                        // HUD back to LISTENING while a fresh phone is connected.
                        if (connSeq.get() == myConn) {
                            _peer.value = null
                            _status.value = Status.LISTENING
                        }
                    }
                }
            }
        }.start(wait = false)
        server = s
        startMdnsAdvertise()
        // Beacon re-resolves its own IPv4 each tick now, so we can fire it
        // immediately even when the local IP isn't ready yet -- the first
        // few ticks may be no-ops while DHCP completes and the beacon will
        // pick up the address as soon as it's assigned.
        udpBeacon.start(hudPort = PORT)
        // Active "I'm looking for you" probe runs in parallel so the phone
        // sees us via at least one of the two channels even on hotspots
        // that filter the beacon direction.
        phoneFinder.start()
        // Self-heal watchdog: a tester reported the link dies after ~2 min
        // and the HUD has to be rebooted before EUC Planet can reconnect.
        // The most plausible culprits are (a) a silently-dead Ktor socket
        // after a network blip, and (b) a DHCP IP renew that the mDNS
        // advertise and on-screen banner never picked up. The watchdog
        // covers both: probes localhost:PORT every WATCHDOG_INTERVAL_MS,
        // and full-restarts after WATCHDOG_FAIL_THRESHOLD consecutive
        // misses; on every tick it also re-resolves the local IPv4 and
        // re-broadcasts mDNS if it changed.
        watchdogFailStreak = 0
        watchdogJob = scope.launch { runWatchdog() }
    }

    private suspend fun doStop() {
        watchdogJob?.cancel()
        watchdogJob = null
        udpBeacon.stop()
        phoneFinder.stop()
        try { jmdns?.close() } catch (_: Throwable) {}
        jmdns = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
        try { server?.stop(1_000L, 2_000L) } catch (_: Throwable) {}
        server = null
        _peer.value = null
        _status.value = Status.LISTENING
        releaseWakeLock()
        releaseWifiLock()
    }

    /** Acquire a partial CPU wake lock so the link can't be frozen by a
     *  device suspend window. Idempotent; safe to call on every (re)start. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock acquire failed: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    /** Acquire a high-performance / low-latency WiFi lock so the radio stays out
     *  of DTIM power-save while the link is up. Idempotent and best-effort: if it
     *  can't be acquired we still run, the link just risks the power-save drops.
     *  Re-checked each watchdog tick so a lost lock is re-taken without a reboot. */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "eucplanet-hud-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wifi lock acquire failed: ${t.message}")
        }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wifiLock = null
    }

    /** Watchdog loop. Two jobs per tick:
     *
     *   1. Re-resolve the local IPv4. If it changed (hotspot DHCP renew,
     *      WiFi reconnect on a new subnet), push the new value into
     *      [_localIp] so the on-screen banner refreshes, and rebuild the
     *      mDNS advertise so phones looking us up via mDNS resolve to the
     *      new address.
     *   2. TCP-probe `127.0.0.1:PORT` to confirm the Ktor server is still
     *      accepting connections. If the probe fails [WATCHDOG_FAIL_THRESHOLD]
     *      ticks in a row, tear everything down and restart the server.
     *      A single probe miss is tolerated because localhost can blip
     *      during Android suspend windows; consecutive misses is the
     *      signal that something's actually wrong. */
    private suspend fun runWatchdog() {
        // delay() is a cancellation point, so the parent watchdogJob cancel
        // unwinds the loop without an explicit isActive check.
        while (true) {
            delay(WATCHDOG_INTERVAL_MS)
            // 0. Re-assert the power locks (idempotent) in case the OS reclaimed
            //    the WiFi lock -- the radio dropping to power-save is the silent
            //    "unreachable but localhost-alive" killer this watchdog can't see.
            acquireWakeLock()
            acquireWifiLock()
            // 1. IP-change handling.
            val current = pickLocalIp()
            val prior = _localIp.value
            if (!current.isNullOrBlank() && current != prior) {
                Log.i(TAG, "watchdog: local IP changed $prior -> $current; refreshing mDNS")
                _localIp.value = current
                try { jmdns?.close() } catch (_: Throwable) {}
                jmdns = null
                try { multicastLock?.release() } catch (_: Throwable) {}
                multicastLock = null
                startMdnsAdvertise()
            } else if (jmdns == null && !current.isNullOrBlank()) {
                // mDNS advertise failed at startup (or got torn down) but the
                // IP is fine -- revive it so phones browsing _eucplanet._tcp
                // can still resolve us without a server restart.
                Log.i(TAG, "watchdog: mDNS advertise was down; reviving")
                startMdnsAdvertise()
            }
            // 2. Server-liveness probe.
            val alive = tcpProbe("127.0.0.1", PORT)
            if (alive) {
                watchdogFailStreak = 0
            } else {
                watchdogFailStreak++
                Log.w(TAG, "watchdog: localhost:$PORT probe miss (streak=$watchdogFailStreak)")
                if (watchdogFailStreak >= WATCHDOG_FAIL_THRESHOLD) {
                    Log.w(TAG, "watchdog: server appears wedged after $watchdogFailStreak " +
                        "consecutive misses; forcing restart")
                    watchdogFailStreak = 0
                    watchdogRestarts++
                    // Restart in its own coroutine so the watchdog loop itself
                    // doesn't suspend on the start/stop lock that doStart()
                    // takes. If the watchdog held its own lifecycle-lock here
                    // we'd deadlock with a concurrent stop() coming from the
                    // activity onDestroy.
                    scope.launch { lifecycleLock.withLock {
                        doStop()
                        doStart()
                    } }
                    // Exit this iteration; doStart() will spawn a fresh watchdog.
                    return
                }
            }
        }
    }

    /** Synchronous TCP connect with a tight timeout. Used by the watchdog to
     *  check the Ktor listener is still accepting; we don't need to send any
     *  data, the three-way handshake completing is enough proof. */
    private fun tcpProbe(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    private fun startMdnsAdvertise() {
        val ip = _localIp.value ?: return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val md = JmDNS.create(Inet4Address.getByName(ip))
            val txt = mapOf(
                HudDiscovery.TXT_VERSION to HudState.PROTOCOL_VERSION.toString(),
                HudDiscovery.TXT_STATE_PATH to HudDiscovery.PATH_STATE
            )
            val info = ServiceInfo.create(
                HudDiscovery.SERVICE_TYPE,
                "motoeye-hud",
                PORT,
                0, 0,
                txt
            )
            md.registerService(info)
            jmdns = md
            Log.i(TAG, "mDNS advertised on $ip:$PORT")
        } catch (t: Throwable) {
            // Advertise is best-effort: even if it fails, the manual IP path
            // still works because we display the IP on screen.
            Log.w(TAG, "mDNS advertise failed: ${t.message}")
        }
    }

    /** Pick the first non-loopback IPv4 from any active interface. On the
     *  Motoeye E6 this is the wlan0 interface joining the rider's hotspot;
     *  on the emulator it's whatever the host gave us. */
    private fun pickLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "pickLocalIp failed: ${t.message}")
        null
    }
}
