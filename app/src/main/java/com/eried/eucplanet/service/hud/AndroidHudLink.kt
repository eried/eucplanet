package com.eried.eucplanet.service.hud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.hud.protocol.HudDebug
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.platform.getString
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import com.eried.eucplanet.util.AppForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jetbrains.compose.resources.StringResource
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/*
 * The Android side of the HUD link's ports (shared HudLinkPorts.kt): the
 * same OkHttp, JmDNS, ConnectivityManager and WifiManager calls HudServer
 * made itself before it moved to shared code for the iPhone.
 */

private const val TAG = "HudServer"

/** The WebSocket client, on OkHttp. */
class AndroidHudSockets : HudSockets {
    // No fixed pingInterval here: it is chosen per-connection in
    // HudServer.streamUntilClosed from the app's foreground/background state
    // (see PING_FOREGROUND_MS / PING_BACKGROUND_MS). newBuilder() reuses this
    // client's connection pool + dispatcher, so a per-dial ping override is cheap.
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private class Socket(val ws: WebSocket) : HudSocket {
        // OkHttp returns false when the outbound queue is over its bound.
        override fun send(text: String): Boolean = ws.send(text)
        override fun close(code: Int, reason: String?) { ws.close(code, reason) }
        override fun cancel() = ws.cancel()
    }

    override fun open(url: String, pingMs: Long, events: HudSocketEvents): HudSocket {
        val req = Request.Builder()
            .url(url)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen(Socket(webSocket))
            override fun onMessage(webSocket: WebSocket, text: String) = events.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                events.onClosing(Socket(webSocket), code, reason)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                events.onFailure(t, response?.code)
        }
        // Per-connection ping tolerance from the foreground/background state;
        // newBuilder() shares this client's pool + dispatcher so this is cheap.
        return Socket(
            http.newBuilder()
                .pingInterval(pingMs, TimeUnit.MILLISECONDS)
                .build()
                .newWebSocket(req, listener)
        )
    }
}

/** The mDNS browse, on JmDNS. */
class AndroidHudMdns(private val context: Context) : HudMdns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    /** Resolve `_eucplanet._tcp.local.` on whatever subnet we have. Returns
     *  `host:port` of the first matching HUD with a compatible protocol
     *  version, or null if nothing answered within [timeoutMs].
     *
     *  Each call opens its own JmDNS instance so a network change between
     *  attempts (rider switching hotspot on, walking out of range) gets a
     *  fresh socket bound to the current interface. JmDNS doesn't always
     *  rebind cleanly when the underlying address changes. */
    override suspend fun resolve(timeoutMs: Long, serviceInfoTimeoutMs: Long): String? {
        ensureMulticastLock()
        // Bind JmDNS to the actual LAN interface(s). JmDNS.create() with no
        // address binds via the default route, which on a WiFi that has no
        // internet is the CELLULAR network - so the mDNS query leaves the wrong
        // interface, never reaches the HUD's LAN, and discovery falls back to
        // the slower subnet probe (the "works outside the shop only" report).
        // One instance per site-local address covers a WiFi + hotspot phone at
        // once; if we can't find any, fall back to the library default so we're
        // never worse than before.
        val binds: List<InetAddress?> = mdnsBindAddresses().ifEmpty { listOf(null) }
        val resolved = kotlinx.coroutines.CompletableDeferred<String?>()
        val instances = mutableListOf<JmDNS>()
        try {
            for (bind in binds) {
                val md = try {
                    if (bind == null) JmDNS.create() else JmDNS.create(bind)
                } catch (t: Throwable) {
                    Log.w(TAG, "mDNS create on ${bind?.hostAddress ?: "default"} failed: ${t.message}")
                    continue
                }
                instances.add(md)
                md.addServiceListener(HudDiscovery.SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        md.requestServiceInfo(event.type, event.name, serviceInfoTimeoutMs)
                    }
                    override fun serviceRemoved(event: ServiceEvent) {}
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val ipv4 = info.inet4Addresses?.firstOrNull()
                        if (ipv4 != null) {
                            if (HudMdns.speaks(info.getPropertyString(HudDiscovery.TXT_VERSION))) {
                                resolved.complete("${ipv4.hostAddress}:${info.port}")
                            }
                        }
                    }
                })
            }
            if (instances.isEmpty()) return null
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { resolved.await() }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Another channel won the race. Catching this as a Throwable (as
            // this did) swallowed the cancellation, so the job carried on and
            // reported itself as a timeout it never reached.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS resolve failed: ${t.message}")
            return null
        } finally {
            // close() unregisters listeners and frees the multicast socket,
            // and it BLOCKS for about two seconds sending goodbyes. Closing it
            // here put those two seconds between knowing the answer and
            // dialling it, because coroutineScope waits for this child: every
            // connect paid it, on every network. Hand the close to this
            // port's own scope so discovery is free to get on with it.
            val toClose = instances.toList()
            if (toClose.isNotEmpty()) {
                scope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    toClose.forEach { md -> runCatching { md.close() } }
                }
            }
        }
    }

    override fun release() {
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }

    /**
     * Site-local IPv4 addresses on real LAN interfaces (WiFi STA and the phone
     * hotspot/AP), excluding loopback and mobile-data/virtual interfaces. JmDNS
     * binds to these directly so discovery works on a WiFi with no internet,
     * where Android makes cellular the default network and a plain
     * JmDNS.create() would bind the wrong interface. Mirrors the interface
     * filter in AndroidHudNetwork.candidateIpv4Cidrs().
     */
    private fun mdnsBindAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("pdp") || name.startsWith("clat") ||
                    name.startsWith("tun") || name.startsWith("dummy")
                ) return@forEach
                nif.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        out.add(a)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "mDNS bind-address enumeration failed: ${it.message}") }
        return out.toList()
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

    private companion object {
        const val MULTICAST_LOCK_TAG = "eucplanet-hud-discovery"
    }
}

/** The phone's Wi-Fi: its subnets, a TCP probe, the network callback and the radio lock. */
class AndroidHudNetwork(private val context: Context) : HudNetwork {

    /** Currently-registered ConnectivityManager callback; cleared on unwatch. */
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** WiFi networks that carry internet, i.e. the rider's own home / other AP
     *  rather than the HUD's. The callback watches every WiFi network (see
     *  [watch]), so this is what still tells a STA transition apart from the
     *  HUD's own network coming and going. */
    private val staNetworks: MutableSet<Network> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

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
     * Every local IPv4 /prefix the phone has, as `ip/prefix` strings.
     *
     * Two sources, deduped:
     *  1. The active network's LinkProperties (fast path for a phone that's a
     *     normal WiFi client).
     *  2. A full NetworkInterface enumeration, filtered to up, non-loopback,
     *     non-cellular interfaces with a site-local (private) IPv4. This is the
     *     ONLY way to see the softAP interface when the phone is the hotspot,
     *     because that interface is never the `activeNetwork`.
     *
     * Cellular / virtual interfaces are skipped by name so we don't waste a
     * 254-host sweep on a carrier subnet the HUD can't be on.
     */
    override fun candidateIpv4Cidrs(): List<String> {
        val out = LinkedHashSet<String>()

        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val props = active?.let { cm.getLinkProperties(it) }
            props?.linkAddresses
                ?.firstOrNull {
                    val a = it.address
                    a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress
                }
                ?.let { out.add("${it.address.hostAddress}/${it.prefixLength}") }
        }

        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                val name = nif.name.lowercase()
                // Mobile-data and virtual interfaces never host the HUD.
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("pdp") || name.startsWith("clat") ||
                    name.startsWith("tun") || name.startsWith("dummy")
                ) return@forEach
                nif.interfaceAddresses.forEach { ia ->
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        out.add("${a.hostAddress}/${ia.networkPrefixLength}")
                    }
                }
            }
        }.onFailure { hudLinkNote("HudSubnetProbe", "interface enumeration failed: ${it.message}") }

        return out.toList()
    }

    /**
     * Plain blocking connect with the socket's own timeout. Not kotlinx's
     * `withTimeoutOrNull`: Socket.connect does not honour coroutine
     * cancellation and an interrupted one leaks a file descriptor.
     */
    override fun tryTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrElse { false }
    }

    /**
     * Watch the system for network availability changes while the link is
     * enabled. What each change means for the link is HudServer's
     * (its networkEvents); this is the callback under it.
     *
     * Track every WiFi network, NOT only the internet-capable ones. Requiring
     * NET_CAPABILITY_INTERNET here was a real bug: when the phone joins the HUD's
     * own AP it joins a network with no internet, so the one network whose loss
     * can strand this link was the one network this callback could never see.
     * [HudUdpListener] already omits the capability for the same reason;
     * these two now agree.
     *
     * The interference detector still wants only the rider's OWN home / other
     * WiFi (a STA transition on a single radio re-tunes the hotspot and blips
     * the HUD). Internet-capable WiFi is that signal, so it is tracked in
     * [staNetworks] from the capability callback and fed from there, rather
     * than from the request filter.
     */
    override fun watch(events: HudNetworkEvents) {
        if (netCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                events.onAvailable()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Read here, not in onAvailable, because capabilities are only
                // known a beat after a network appears.
                val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (internet) {
                    if (staNetworks.add(network)) {
                        events.onOwnWifiJoined()
                    }
                } else {
                    staNetworks.remove(network)
                }
            }
            override fun onLost(network: Network) {
                val wasSta = staNetworks.remove(network)
                events.onLost(wasSta)
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                if (!staNetworks.contains(network)) return
                events.onOwnWifiLeaving(maxMsToLive)
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            netCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed: ${t.message}")
        }
    }

    override fun unwatch() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    override fun holdRadioAwake(hold: Boolean) {
        if (hold) acquireWifiPerfLock() else releaseWifiPerfLock()
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

    private fun releaseWifiPerfLock() {
        try { wifiLock?.release() } catch (_: Throwable) {}
        wifiLock = null
    }
}

/** The phone around the link: the screen, the GPS fix, its texts, the HUD app it carries. */
class AndroidHudPhone(
    private val context: Context,
    private val tripRepository: TripRepository,
    private val appSender: HudAppSender,
) : HudPhone {
    override val foreground: StateFlow<Boolean> get() = AppForeground.isForeground

    /** True when the app UI is actually on-screen (process importance
     *  FOREGROUND), not merely the ride foreground service running
     *  (FOREGROUND_SERVICE). Once the UI is gone, Samsung / Android 16 throttle
     *  the app's socket I/O, so the tight 5s ping window is only safe with the UI
     *  up. Fails open to foreground so a detection miss keeps today's behaviour. */
    override fun uiForeground(): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Exception) {
        true
    }

    override fun location(): PhoneLocation? = tripRepository.currentLocation.value?.toPhoneLocation()

    override fun setMapLinked(linked: Boolean) = tripRepository.setHudMapLinked(linked)

    override fun text(res: StringResource): String = context.getString(res)

    override fun text(res: StringResource, args: List<Any>): String =
        context.getString(res, *args.toTypedArray())

    override fun shortTime(epochMs: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(epochMs))

    override fun debug(name: String): String? = HudDebug.read(name)

    override fun onLinkDown() = appSender.reset()
}
