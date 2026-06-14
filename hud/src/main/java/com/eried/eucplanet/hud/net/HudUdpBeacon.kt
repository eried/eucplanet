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

    /**
     * [hudIpv4] is the address the phone should dial back on. We send it
     * verbatim in the payload because the HUD's outbound socket may bind
     * 0.0.0.0 and the source address on a broadcast frame isn't guaranteed
     * to match the address the phone can route to.
     * [hudPort] is the WebSocket port the phone should connect to (the
     * one the HUD's Ktor server is listening on).
     */
    fun start(hudIpv4: String, hudPort: Int) {
        stop()
        val payload = "${HudDiscovery.BEACON_PREFIX} ip=$hudIpv4 port=$hudPort\n"
        val bytes = payload.toByteArray(Charsets.US_ASCII)
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
            try {
                while (isActive) {
                    val targets = broadcastTargets()
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, target, port))
                        }.onFailure {
                            Log.v(TAG, "beacon to $target failed: ${it.message}")
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
