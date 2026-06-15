package com.eried.eucplanet.hud.net

import android.util.Log
import com.eried.eucplanet.hud.protocol.HudDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * Sends a tiny UDP "I'm a HUD at this IP" broadcast every
 * [HudDiscovery.BEACON_INTERVAL_MS] so the phone can find this HUD without
 * the rider having to read the IP off the on-screen banner and type it in.
 *
 * Works alongside the existing mDNS service registration -- mDNS is the
 * cleaner discovery on standard WiFi networks but it's blocked on most
 * phone hotspots / carrier-grade NAT hotspots (which is exactly where
 * riders have a HUD set up). UDP broadcast cuts through both because
 * the packet is unicast-addressed to the subnet's broadcast address.
 *
 * The beacon goes to TWO destinations per tick:
 *   1. The global broadcast 255.255.255.255 -- works on most hotspot
 *      firmwares, and on any router that doesn't block directed broadcast.
 *   2. Every reachable interface's subnet broadcast (e.g. 192.168.43.255
 *      on a 192.168.43.x hotspot) -- some hardened APs drop the global
 *      broadcast but allow the subnet one.
 *
 * Lifecycle: [start] is called from `HudServer.doStart()` after the local
 * IPv4 is resolved; [stop] cancels the job. Payload format is the canonical
 * `HudDiscovery.BEACON_PREFIX` ASCII line, terminated with `\n`.
 */
class HudUdpBeacon(
    private val port: Int = HudDiscovery.BEACON_UDP_PORT,
    private val intervalMs: Long = HudDiscovery.BEACON_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Stats for the HUD-side diagnostics card. Atomic counters so the UI
     *  can subscribe without a flow contention surface. */
    @Volatile var lastTickAtMs: Long = 0L; private set
    @Volatile var totalTicks: Long = 0L; private set
    @Volatile var lastResolvedIp: String = ""; private set

    /**
     * Start the beacon. [hudPort] is the WebSocket port the phone should
     * connect to (the one the HUD's Ktor server is listening on). The HUD's
     * own IPv4 is RE-RESOLVED every tick rather than baked in at start,
     * because the Motoeye E6's hotspot stack frequently DHCPs a new
     * address mid-session (rider's phone reconnects, AP renews the lease)
     * and a beacon stuck on the old IP would silently lure the phone to a
     * dead address.
     */
    fun start(hudPort: Int) {
        stop()
        Log.i(TAG, "starting beacon on UDP $port for HUD port $hudPort")
        job = scope.launch {
            // Open ONCE, reuse the socket across ticks. Re-creating a
            // socket every 2 s was burning ~30 file descriptors / min on
            // the Motoeye E6's flaky network stack.
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrElse {
                Log.w(TAG, "couldn't open beacon socket", it)
                return@launch
            }
            Log.i(TAG, "beacon socket opened (src port ${socket.localPort})")
            try {
                while (isActive) {
                    val ip = pickLocalIpv4()
                    if (ip.isBlank()) {
                        Log.v(TAG, "no IPv4 yet; will retry")
                        delay(intervalMs)
                        continue
                    }
                    lastResolvedIp = ip
                    val payload = "${HudDiscovery.BEACON_PREFIX} ip=$ip port=$hudPort\n"
                    val bytes = payload.toByteArray(Charsets.US_ASCII)
                    val targets = broadcastTargets()
                    var sentOk = 0
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, target, port))
                            sentOk++
                        }.onFailure {
                            Log.v(TAG, "beacon to $target failed: ${it.message}")
                        }
                    }
                    if (sentOk > 0) {
                        lastTickAtMs = System.currentTimeMillis()
                        totalTicks++
                        if (totalTicks <= 3L || totalTicks % 10 == 0L) {
                            Log.i(TAG, "beacon tick #$totalTicks ip=$ip targets=${targets.size} sentOk=$sentOk")
                        }
                    }
                    delay(intervalMs)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** First non-loopback IPv4 across the up interfaces. Re-walked each
     *  tick so a DHCP renew is reflected within one beacon interval. */
    private fun pickLocalIpv4(): String {
        return runCatching {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return@runCatching ""
            while (ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress) {
                        return@runCatching a.hostAddress.orEmpty()
                    }
                }
            }
            ""
        }.getOrDefault("")
    }

    /**
     * 255.255.255.255 plus every IPv4 interface's subnet broadcast
     * (e.g. 192.168.43.255). Some APs filter one or the other; sending
     * to both is cheap and maximises the odds of one getting through.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia: InterfaceAddress in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b is Inet4Address) out += b
                }
            }
        }
        return out
    }

    companion object { private const val TAG = "HudUdpBeacon" }
}
