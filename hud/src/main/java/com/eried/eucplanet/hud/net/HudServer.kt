package com.eried.eucplanet.hud.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudCommand
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
import java.net.NetworkInterface
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
        _localIp.value = pickLocalIp()
        val s = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
                // Heartbeat: phone or HUD network can go away silently. Ping
                // every 15s so the OS surfaces a broken TCP connection
                // within a reasonable window without spamming the wire.
                // Millis form because the Duration property accessor names
                // moved across Ktor 2.x; millis is stable.
                pingPeriodMillis = 15_000L
                timeoutMillis = 30_000L
            }
            routing {
                webSocket(HudDiscovery.PATH_STATE) {
                    val remote = call.request.local.remoteHost
                    Log.i(TAG, "phone connected: $remote")
                    _peer.value = remote
                    _status.value = Status.CONNECTED

                    // Greet the phone with our version. The phone uses
                    // this to decide whether to show "update HUD" or
                    // "update Phone" hints. Older HUD APKs sent a Pair
                    // without protocol fields; default 0/0 there means
                    // "treat as the pre-split baseline 1.0".
                    try {
                        send(json.encodeToString<HudCommand>(
                            HudCommand.Pair(
                                hudId = "motoeye-hud",
                                hudVersion = com.eried.eucplanet.hud.BuildConfig.VERSION_NAME,
                                hudProtocolMajor = HudState.PROTOCOL_MAJOR,
                                hudProtocolMinor = HudState.PROTOCOL_MINOR
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
                    } finally {
                        sender.cancel()
                        Log.i(TAG, "phone disconnected: $remote")
                        _peer.value = null
                        _status.value = Status.LISTENING
                    }
                }
            }
        }.start(wait = false)
        server = s
        startMdnsAdvertise()
    }

    private suspend fun doStop() {
        try { jmdns?.close() } catch (_: Throwable) {}
        jmdns = null
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
        try { server?.stop(1_000L, 2_000L) } catch (_: Throwable) {}
        server = null
        _peer.value = null
        _status.value = Status.LISTENING
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
