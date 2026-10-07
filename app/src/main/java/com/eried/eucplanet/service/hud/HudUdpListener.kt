package com.eried.eucplanet.service.hud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Listens for the HUD's UDP discovery beacon on [HudDiscovery.BEACON_UDP_PORT]
 * and publishes the most recent (ip, port, receivedAtMs) sighting via
 * [latest].
 *
 * Why a separate UDP path instead of just relying on mDNS: phone-hosted
 * hotspots routinely block multicast (which is how mDNS works), and so do
 * most carrier mobile hotspots. UDP unicast broadcast usually still gets
 * through. Running both paths in parallel costs almost nothing on the
 * phone (one socket on one port) and rescues the rider whenever one path
 * is blocked.
 *
 * The socket binds 0.0.0.0:28079 with SO_REUSEADDR set, so multiple
 * listeners on the same phone (e.g. the live service + a manual scan-now
 * triggered from settings) don't collide. SO_BROADCAST is also set so
 * directed-broadcast packets aren't filtered out before they reach our
 * recv buffer.
 *
 * Lifecycle: start() launches the receive loop; stop() cancels it. The
 * loop self-recovers from any unexpected socket exception by closing and
 * re-opening with a 1 s backoff, so an in-flight wifi-state change can't
 * permanently silence the listener.
 */
@Singleton
class HudUdpListener @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val port: Int = HudDiscovery.BEACON_UDP_PORT
    data class Sighting(val ip: String, val port: Int, val receivedAtMs: Long)

    private val _latest = MutableStateFlow<Sighting?>(null)
    val latest: StateFlow<Sighting?> = _latest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Diagnostic counters surfaced on the HUD-settings status panel so the
     *  rider can see whether broadcasts are arriving at all. */
    @Volatile var totalReceived: Long = 0L; private set
    @Volatile var lastReceiveAtMs: Long = 0L; private set
    @Volatile var lastBindError: String = ""; private set

    /** Rate limit for the beacon trace; see the receive loop. */
    private var lastNotedSourceIp: String = ""
    private var lastNotedAtMs: Long = 0L

    fun start() {
        if (job != null) return
        acquireMulticastLock()
        watchNetworkChanges()
        job = scope.launch {
            while (isActive) {
                runCatching { runListenLoop() }
                    .onFailure {
                        Log.w(TAG, "UDP listener crashed, restarting", it)
                        lastBindError = it.message ?: it::class.simpleName.orEmpty()
                        hudLinkNote(TAG, "Beacon listener: bind/receive failed: $lastBindError")
                    }
                // Brief backoff so a persistent failure (e.g. permission
                // denied) doesn't spin a tight retry loop and chew battery.
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseMulticastLock()
        unwatchNetworkChanges()
        _latest.value = null
    }

    /**
     * Many Android WiFi drivers silently drop inbound *broadcast* (yes,
     * broadcast, not just multicast) unless a `MulticastLock` is held.
     * The lock is reference-counted and cheap; we acquire it for the
     * lifetime of the listener and release on stop.
     */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        runCatching {
            val wifi = appContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching
            val lock = wifi.createMulticastLock(LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            hudLinkNote(TAG, "Beacon listener: multicast lock acquired")
        }.onFailure {
            // Without the lock many drivers drop inbound broadcast entirely, so
            // this failing explains a silent beacon channel by itself.
            hudLinkNote(TAG, "Beacon listener: NO multicast lock (${it.message}), " +
                "inbound broadcast may be dropped")
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /**
     * Watch for WiFi connection state changes so a rider's mid-session
     * reconnect (hotspot toggle, AP switch) kicks the listener cleanly.
     * Without this the bound socket can survive a network change in a
     * silently-broken state.
     */
    private fun watchNetworkChanges() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return@runCatching
            // NO NET_CAPABILITY_INTERNET: the HUD often rides a WiFi that has
            // no internet (a Faraday-cage shop AP, or the phone's own hotspot),
            // and we want to recycle the listener when the rider joins exactly
            // those. Requiring internet here meant the clean recycle never fired
            // in the environment the HUD is most used in.
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    hudLinkNote(TAG, "Beacon listener: WiFi available, recycling")
                    job?.cancel()
                    job = scope.launch {
                        kotlinx.coroutines.delay(250L) // let socket fully release
                        while (isActive) {
                            runCatching { runListenLoop() }
                            kotlinx.coroutines.delay(1_000L)
                        }
                    }
                }
                override fun onLost(network: Network) {
                    // A HUD that stops answering right here was not lost by us:
                    // the phone changed networks underneath the listener.
                    hudLinkNote(TAG, "Beacon listener: WiFi lost")
                }
            }
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        }.onFailure { hudLinkNote(TAG, "Beacon listener: network callback failed: ${it.message}") }
    }

    private fun unwatchNetworkChanges() {
        val cb = networkCallback ?: return
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
    }

    private fun runListenLoop() {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 0
            socket.bind(InetSocketAddress(port))
            lastBindError = ""
            hudLinkNote(TAG, "Beacon listener: bound to 0.0.0.0:$port")
            val buf = ByteArray(512)
            while (job?.isCancelled == false) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet) // blocks
                val text = String(packet.data, 0, packet.length, Charsets.US_ASCII).trim()
                val srcIp = packet.address.hostAddress.orEmpty()
                parse(text, srcIp)?.let {
                    _latest.value = it
                    totalReceived++
                    val now = System.currentTimeMillis()
                    lastReceiveAtMs = now
                    // First beacon from a source, then one a minute. Enough to
                    // prove the channel is alive and to show it going quiet,
                    // without burying the rest of the capture at 1 Hz.
                    if (srcIp != lastNotedSourceIp || now - lastNotedAtMs > BEACON_NOTE_INTERVAL_MS) {
                        lastNotedSourceIp = srcIp
                        lastNotedAtMs = now
                        hudLinkNote(TAG, "Beacon RX from $srcIp -> ${it.ip}:${it.port} (#$totalReceived)")
                    }
                }
            }
        }
    }

    /**
     * Beacon text: `EUCPLANET-HUD v1 ip=10.80.67.125 port=28080 [...]`.
     * The `ip=` field is the canonical "dial this address" hint -- we
     * still keep the packet's source address as a fallback for broken
     * HUDs that didn't manage to substitute their own IP into the
     * payload.
     */
    private fun parse(text: String, sourceIp: String): Sighting? {
        if (!text.startsWith(HudDiscovery.BEACON_PREFIX)) return null
        val rest = text.removePrefix(HudDiscovery.BEACON_PREFIX).trim()
        val kv = rest.split(' ').mapNotNull { piece ->
            val eq = piece.indexOf('=')
            if (eq <= 0) null else piece.substring(0, eq) to piece.substring(eq + 1)
        }.toMap()
        val ip = kv["ip"]?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: sourceIp.takeIf { it.isNotBlank() }
            ?: return null
        val port = kv["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: HudDiscovery.DEFAULT_PORT
        return Sighting(ip = ip, port = port, receivedAtMs = System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "HudUdpListener"
        private const val LOCK_TAG = "eucplanet-hud-udp-listener"
        /** Beacons land about once a second; one trace line a minute is enough
         *  to show the channel alive and to show it going quiet. */
        private const val BEACON_NOTE_INTERVAL_MS = 60_000L
    }
}
