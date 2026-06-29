package com.eried.eucplanet.hud.net

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDebug
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.protocol.LinkHealth
import com.eried.eucplanet.hud.protocol.LinkVerdict
import com.eried.eucplanet.hud.protocol.LinkWatchdog
import com.eried.eucplanet.hud.protocol.RecoveryStep
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
import java.net.InetAddress
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
        /** Faster watchdog cadence once the link looks unhealthy, so an
         *  off-air radio is detected and the recovery ladder escalates within
         *  seconds instead of the lazy 10 s healthy interval. */
        private const val RECOVERY_INTERVAL_MS: Long = 3_000L
        /** Timeout for the "can I still reach the phone I was talking to"
         *  reachability probe. Short: the hotspot link is sub-10 ms when up. */
        private const val PEER_PROBE_TIMEOUT_MS: Int = 1_200
        /** Consecutive off-air ticks tolerated before the recovery ladder
         *  starts. One grace tick rides out a single transient peer-probe miss
         *  without kicking the radio. */
        private const val OFF_AIR_GRACE_TICKS: Int = 1
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
    /** Consecutive watchdog ticks classified [LinkVerdict.OFF_AIR]. Drives the
     *  recovery ladder. SEPARATE from [watchdogFailStreak] on purpose: the old
     *  loopback probe stays green when the radio is off-air, so if both shared
     *  one counter the green loopback result would keep zeroing out the real
     *  off-air signal and nothing would ever recover. */
    @Volatile private var offAirStreak: Int = 0
    /** Wall-clock when the current off-air episode began (0 = on the air). Used
     *  to report the air-gap duration in the recovery note. */
    @Volatile private var offAirSinceMs: Long = 0L
    /** How many recovery actions of each kind we've taken this episode, for the
     *  recovery note the HUD reports back to the phone. */
    @Volatile private var rcRestart: Int = 0
    @Volatile private var rcReassoc: Int = 0
    @Volatile private var rcToggle: Int = 0
    /** Number of off-air episodes we've auto-recovered from this run -- surfaced
     *  on the stats card so a "had to reboot" report can instead read "recovered
     *  N times on its own". */
    @Volatile var offAirRecoveries: Int = 0; private set
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
                    // If we just self-healed a WiFi drop, ride the summary back
                    // on the Pair so it lands in the rider's shareable phone
                    // diagnostics (the phone logs Pair as a NOTE). Consume-once.
                    val recoveryNote = HudDiag.consumeRecoveryNote()
                    if (recoveryNote.isNotBlank()) {
                        HudDiag.log("pair", "reporting recovery to phone: $recoveryNote")
                    }
                    try {
                        send(json.encodeToString<HudCommand>(
                            HudCommand.Pair(
                                hudId = "motoeye-hud",
                                hudVersion = com.eried.eucplanet.hud.BuildConfig.VERSION_NAME,
                                hudProtocolMajor = sendMajor,
                                hudProtocolMinor = sendMinor,
                                recoveryNote = recoveryNote
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

    /**
     * Self-heal watchdog, reworked to be REACHABILITY-driven rather than
     * loopback-driven.
     *
     * The old loop's only liveness check was a TCP probe to `127.0.0.1`, which
     * succeeds whenever the in-process Ktor server is alive -- completely
     * independent of whether `wlan0` is still on the rider's hotspot. So when
     * the phone left home-WiFi range and the HUD's radio dropped off the
     * softAP, the probe stayed green, the watchdog did nothing, and the rider
     * had to reboot the Motoeye (diagnosed from the 2026-06-29 tester log: the
     * HUD's UDP beacon vanished at 07:18:52 and never returned).
     *
     * Now each tick gathers real radio signals (WiFi association + a probe to
     * the phone we last talked to) and feeds them to [LinkWatchdog.assess]:
     *   - HEALTHY        -> reset streaks, refresh IP/mDNS, relax cadence.
     *   - SERVER_WEDGED  -> radio fine but listener dead: restart the server
     *                       (the old behaviour, now correctly scoped to the
     *                       one case it actually fixes).
     *   - OFF_AIR        -> climb the recovery ladder (restart sockets ->
     *                       reassociate -> toggle WiFi) until the link is back,
     *                       logging every step and reporting a one-line summary
     *                       to the phone on the next pair.
     *
     * While a phone is actively CONNECTED the link is healthy by definition
     * (the live WebSocket rides wlan0), so we short-circuit to HEALTHY and
     * never let a transient association read trigger recovery on a good link.
     */
    private suspend fun runWatchdog() {
        // delay() is a cancellation point, so the parent watchdogJob cancel
        // unwinds the loop without an explicit isActive check.
        while (true) {
            // Cadence: lazy when healthy, brisk while we suspect or repair an
            // off-air radio so the ladder escalates in seconds, not tens of them.
            val interval = if (offAirStreak > 0 || watchdogFailStreak > 0)
                RECOVERY_INTERVAL_MS else WATCHDOG_INTERVAL_MS
            delay(interval)
            // Re-assert the power locks (idempotent) each tick.
            acquireWakeLock()
            acquireWifiLock()

            val health = gatherHealth()
            val verdict = if (_status.value == Status.CONNECTED)
                LinkVerdict.HEALTHY else LinkWatchdog.assess(health)
            HudDiag.log("watchdog",
                "verdict=$verdict ip=${health.localIp ?: "-"} assoc=${health.associated} " +
                    "peerKnown=${health.peerKnown} peerReach=${health.peerReachable} " +
                    "server=${health.serverAlive} offAirStreak=$offAirStreak " +
                    "status=${_status.value}")

            when (verdict) {
                LinkVerdict.HEALTHY -> {
                    watchdogFailStreak = 0
                    if (offAirSinceMs != 0L) finishOffAirEpisode()
                    offAirStreak = 0
                    refreshIpAndMdns()
                }

                LinkVerdict.SERVER_WEDGED -> {
                    // Radio is fine; only the Ktor listener looks dead. Keep the
                    // old consecutive-miss guard so a one-tick blip doesn't churn.
                    offAirStreak = 0
                    if (offAirSinceMs != 0L) finishOffAirEpisode()
                    refreshIpAndMdns()
                    watchdogFailStreak++
                    HudDiag.log("watchdog",
                        "server wedged (streak=$watchdogFailStreak) but radio OK")
                    if (watchdogFailStreak >= WATCHDOG_FAIL_THRESHOLD) {
                        watchdogFailStreak = 0
                        watchdogRestarts++
                        HudDiag.log("watchdog", "restarting Ktor server (#$watchdogRestarts)")
                        // Restart in its own coroutine so the watchdog loop
                        // doesn't suspend on the lifecycle lock doStart() takes
                        // (a concurrent activity-onDestroy stop() would deadlock).
                        scope.launch { lifecycleLock.withLock { doStop(); doStart() } }
                        return
                    }
                }

                LinkVerdict.OFF_AIR -> {
                    watchdogFailStreak = 0
                    if (offAirSinceMs == 0L) beginOffAirEpisode(health)
                    offAirStreak++
                    if (offAirStreak <= OFF_AIR_GRACE_TICKS) {
                        HudDiag.log("watchdog",
                            "off-air suspected (streak=$offAirStreak); one grace tick before acting")
                    } else {
                        val attempt = offAirStreak - OFF_AIR_GRACE_TICKS - 1
                        val step = LinkWatchdog.recoveryStepFor(attempt)
                        HudDiag.log("recovery",
                            "off-air ${airGapSeconds()}s, attempt #$attempt -> $step")
                        executeRecovery(step)
                    }
                }
            }
        }
    }

    /** Gather one tick of link-health signals for [LinkWatchdog.assess]. While
     *  a phone is actively connected the live WebSocket is proof of a good
     *  radio, so we skip the (blocking) reachability probe entirely. */
    private fun gatherHealth(): LinkHealth {
        val connectedNow = _status.value == Status.CONNECTED
        val serverAlive = tcpProbe("127.0.0.1", PORT)
        val localIp = pickLocalIp()
        val associated = if (connectedNow) true else readAssociated()
        val phoneIp = phoneFinder.lastSeenPhoneIp
        val peerKnown = connectedNow || phoneIp.isNotBlank()
        val peerReachable = when {
            connectedNow -> true                                   // live WS = proof
            phoneIp.isBlank() -> false                             // never paired yet
            !associated || localIp.isNullOrBlank() -> false        // no radio to probe over
            else -> isPeerReachable(phoneIp)
        }
        return LinkHealth(
            serverAlive = serverAlive,
            associated = associated,
            localIp = localIp,
            peerKnown = peerKnown,
            peerReachable = peerReachable,
        )
    }

    /** True when `wlan0` reports a completed association with a real DHCP
     *  address. We deliberately do NOT check the BSSID: without location
     *  permission Android redacts it to 02:00:00:00:00:00, which would
     *  false-negative. SupplicantState COMPLETED + a non-zero IP is the
     *  location-free "we're joined" signal. */
    @Suppress("DEPRECATION")
    private fun readAssociated(): Boolean = runCatching {
        val wifi = wifiManager() ?: return false
        val info = wifi.connectionInfo ?: return false
        info.supplicantState == SupplicantState.COMPLETED && info.ipAddress != 0
    }.getOrDefault(false)

    /** On-network reachability probe to the last-seen phone. Blocks up to
     *  [PEER_PROBE_TIMEOUT_MS]; only called from the IO-dispatched watchdog. */
    private fun isPeerReachable(ip: String): Boolean = runCatching {
        InetAddress.getByName(ip).isReachable(PEER_PROBE_TIMEOUT_MS)
    }.getOrDefault(false)

    private fun wifiManager(): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Keep the on-screen IP + mDNS advertise fresh. Lifted out of the old
     *  watchdog loop so both the healthy and server-wedged paths share it. */
    private fun refreshIpAndMdns() {
        val current = pickLocalIp()
        val prior = _localIp.value
        if (!current.isNullOrBlank() && current != prior) {
            HudDiag.log("watchdog", "local IP changed $prior -> $current; refreshing mDNS")
            _localIp.value = current
            try { jmdns?.close() } catch (_: Throwable) {}
            jmdns = null
            try { multicastLock?.release() } catch (_: Throwable) {}
            multicastLock = null
            startMdnsAdvertise()
        } else if (jmdns == null && !current.isNullOrBlank()) {
            HudDiag.log("watchdog", "mDNS advertise was down; reviving")
            startMdnsAdvertise()
        }
    }

    private fun beginOffAirEpisode(h: LinkHealth) {
        offAirSinceMs = System.currentTimeMillis()
        rcRestart = 0; rcReassoc = 0; rcToggle = 0
        HudDiag.log("recovery",
            "OFF-AIR detected (ip=${h.localIp ?: "-"} assoc=${h.associated} " +
                "peerKnown=${h.peerKnown} peerReach=${h.peerReachable}); beginning self-heal")
    }

    private fun airGapSeconds(): String {
        if (offAirSinceMs == 0L) return "0"
        val ms = System.currentTimeMillis() - offAirSinceMs
        return String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
    }

    private fun finishOffAirEpisode() {
        val gap = airGapSeconds()
        offAirRecoveries++
        val actions = buildString {
            if (rcRestart > 0) append("restart x$rcRestart ")
            if (rcReassoc > 0) append("reassoc x$rcReassoc ")
            if (rcToggle > 0) append("toggle x$rcToggle ")
        }.trim().ifEmpty { "no action" }
        val note = "recovered after ${gap}s off-air ($actions) [#$offAirRecoveries]"
        HudDiag.log("recovery", "BACK ON AIR: $note")
        HudDiag.setRecoveryNote(note)
        offAirSinceMs = 0L
    }

    /** Climb one rung of the off-air recovery ladder. Best-effort; every step
     *  is logged so the rider's diagnostics show exactly what was tried. The
     *  WiFi lock is released around the radio-touching steps so a held
     *  low-latency lock can't get in the way of the supplicant's rescan. */
    private suspend fun executeRecovery(step: RecoveryStep) {
        val wifi = wifiManager()
        when (step) {
            RecoveryStep.RESTART_SOCKETS -> {
                rcRestart++
                HudDiag.log("recovery",
                    "step RESTART_SOCKETS: bounce lock + restart beacon/mDNS/finder")
                releaseWifiLock(); acquireWifiLock()
                val ip = pickLocalIp()
                _localIp.value = ip
                try { jmdns?.close() } catch (_: Throwable) {}
                jmdns = null
                try { multicastLock?.release() } catch (_: Throwable) {}
                multicastLock = null
                if (!ip.isNullOrBlank()) startMdnsAdvertise()
                udpBeacon.stop(); udpBeacon.start(hudPort = PORT)
                phoneFinder.stop(); phoneFinder.start()
            }

            RecoveryStep.REASSOCIATE -> {
                rcReassoc++
                HudDiag.log("recovery", "step REASSOCIATE: WifiManager.reconnect()+reassociate()")
                releaseWifiLock()
                @Suppress("DEPRECATION")
                val rc = runCatching { wifi?.reconnect() }.getOrNull()
                @Suppress("DEPRECATION")
                val ra = runCatching { wifi?.reassociate() }.getOrNull()
                HudDiag.log("recovery", "reconnect()=$rc reassociate()=$ra")
                acquireWifiLock()
            }

            RecoveryStep.TOGGLE_WIFI -> {
                if (android.os.Build.VERSION.SDK_INT < 29) {
                    rcToggle++
                    HudDiag.log("recovery", "step TOGGLE_WIFI: setWifiEnabled(false/true) (API<29)")
                    releaseWifiLock()
                    @Suppress("DEPRECATION")
                    runCatching { wifi?.isWifiEnabled = false }
                    delay(1_500L)
                    @Suppress("DEPRECATION")
                    runCatching { wifi?.isWifiEnabled = true }
                    acquireWifiLock()
                } else {
                    // setWifiEnabled is a no-op for apps on API 29+; don't waste
                    // a rung pretending. Fall back to another reassociate.
                    HudDiag.log("recovery",
                        "step TOGGLE_WIFI not permitted on API" +
                            "${android.os.Build.VERSION.SDK_INT}; falling back to REASSOCIATE")
                    executeRecovery(RecoveryStep.REASSOCIATE)
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
