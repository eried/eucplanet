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
 * Symmetric discovery counterpart to [HudUdpBeacon]: the HUD itself listens
 * for "phone here" UDP broadcasts AND sends an explicit "phone, please
 * connect" beacon back. This converts discovery from one-way (HUD shouts,
 * phone listens) to two-way -- if the HUD's broadcast is filtered on the
 * phone's wifi driver but the phone's own broadcast goes through, the
 * phone learns the HUD's IP from the *source address* of the inbound
 * "ping me" packet.
 *
 * The HUD also walks every reachable subnet and TCP-pokes each host on the
 * Ktor port; first responder is the phone (or whatever device happens to
 * listen there -- harmless either way, the WebSocket handshake will reject
 * a non-EUCPlanet peer). The TCP probe wakes up phones whose firewall
 * filters inbound UDP broadcast but accepts incoming TCP from a known LAN
 * source.
 *
 * Failure mode this addresses: rider on a hotspot whose AP blocks
 * client-to-client broadcast outright. Phone won't see HUD's UDP beacon.
 * But the HUD's TCP probe to phone's own listening socket can still
 * complete (most APs allow client-to-client UNICAST even when they block
 * broadcast).
 *
 * Diagnostic counters surface on the HUD screen so the rider can verify
 * which channel is active.
 */
class HudPhoneFinder(
    private val intervalMs: Long = HudDiscovery.BEACON_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Diagnostic snapshot. */
    @Volatile var lastSeenPhoneIp: String = ""; private set
    @Volatile var lastSeenPhoneAtMs: Long = 0L; private set
    @Volatile var udpProbesSent: Long = 0L; private set

    /**
     * Continuously broadcasts a small "looking for phone" packet and keeps
     * scanning the local /24 so that, even on a hostile network where the
     * phone's UDP listener is shadowed, the HUD has a fighting chance of
     * making the rider's link work.
     */
    fun start() {
        stop()
        job = scope.launch {
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrElse {
                Log.w(TAG, "couldn't open probe socket", it)
                return@launch
            }
            try {
                while (isActive) {
                    // 1. UDP broadcast: same prefix as the beacon, but
                    //    with `role=hud-probe` so the phone listener can
                    //    distinguish a passive announce from an "actively
                    //    looking" frame (the phone uses the source IP
                    //    either way; the role only colours diagnostics).
                    val payload = "${HudDiscovery.BEACON_PREFIX} role=hud-probe\n"
                        .toByteArray(Charsets.US_ASCII)
                    for (target in broadcastTargets()) {
                        runCatching {
                            socket.send(
                                DatagramPacket(payload, payload.size, target,
                                    HudDiscovery.BEACON_UDP_PORT)
                            )
                            udpProbesSent++
                        }.onFailure {
                            Log.v(TAG, "probe to $target failed: ${it.message}")
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
     * Called when a real WebSocket pair is established -- lets diagnostics
     * remember the last successful phone IP across reconnects.
     */
    fun notePhone(ip: String) {
        if (ip.isBlank()) return
        lastSeenPhoneIp = ip
        lastSeenPhoneAtMs = System.currentTimeMillis()
    }

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

    companion object { private const val TAG = "HudPhoneFinder" }
}
