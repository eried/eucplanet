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
 * Broadcasts a `role=hud-probe` packet every interval, next to the
 * [HudUdpBeacon] announce, so the phone can learn the HUD's address from
 * the packet source.
 */
class HudPhoneFinder(
    private val intervalMs: Long = HudDiscovery.BEACON_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Continuously broadcasts a small "looking for phone" packet. */
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
