package com.eried.eucplanet.service.hud

import com.eried.eucplanet.hud.protocol.HudDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Slowest discovery channel: probes every host on each local /24 subnet on
 * the HUD WebSocket port and returns the first one that accepts a TCP
 * connection. Runs alongside the UDP beacon and mDNS; a win on either
 * cancels it.
 *
 * Connect attempts run with a bounded fan-out ([MAX_CONCURRENT] at a time),
 * each with a 250 ms timeout, so even a multi-subnet sweep finishes in about
 * a second WITHOUT firing hundreds of simultaneous connections. That cap
 * matters: an unbounded flood saturates the Wi-Fi radio and starves the mDNS
 * multicast browse running in parallel. We don't speak the WebSocket
 * handshake -- a plain TCP open is enough evidence that *something* is
 * listening on the right port; the dial loop then attempts a real handshake
 * and either succeeds or falls back to the next discovery cycle.
 *
 * The probe is gated on the rider opting into auto-discovery and on the faster
 * mDNS / UDP paths not having already found the HUD (a win cancels it). It
 * starts as soon as those are launched - the [MAX_CONCURRENT] cap is what keeps
 * it from congesting the radio, so it needs no start delay.
 */
class HudSubnetProbe(
    private val network: HudNetwork,
) {

    suspend fun probe(port: Int = HudDiscovery.DEFAULT_PORT): String? = withContext(Dispatchers.IO) {
        // Scan EVERY local IPv4 subnet we can see, not just the "active
        // network" one. Critical for the phone-hotspot case: when the phone
        // shares its connection, `activeNetwork` is the CELLULAR route, so the
        // old single-subnet probe scanned a carrier subnet and never saw the
        // HUD sitting on the 192.168.x AP interface. Enumerating interfaces
        // catches the AP (and any real WiFi) subnet too.
        val cidrs = network.candidateIpv4Cidrs()
        if (cidrs.isEmpty()) {
            // No WiFi and no hotspot: nothing the HUD could be on. Worth saying
            // out loud, because every channel goes quiet and the capture would
            // otherwise look like the HUD was missing rather than the network.
            hudLinkNote(TAG, "Subnet probe: no local IPv4 interface (no WiFi, no hotspot)")
            return@withContext null
        }
        val candidates = cidrs.flatMap { expandSubnetIpv4(it) ?: emptyList() }.distinct()
        if (candidates.isEmpty()) {
            hudLinkNote(TAG, "Subnet probe: interfaces $cidrs expand to no hosts")
            return@withContext null
        }
        // The one line that says which network the phone was actually on when
        // discovery ran. Without it, a failed capture cannot tell a HUD that
        // wandered off to another AP from one that is simply out of radio range.
        hudLinkNote(TAG, "Subnet probe: ${candidates.size} hosts across ${cidrs.size} subnet(s) $cidrs:$port")
        // Bound the in-flight connects so the scan doesn't flood the Wi-Fi radio
        // and starve mDNS discovery running in parallel.
        val gate = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            val results = candidates.map { ip ->
                async {
                    gate.withPermit { if (network.tryTcp(ip, port, timeoutMs = 250)) ip else null }
                }
            }.awaitAll()
            results.firstOrNull { it != null }
        }
    }

    /**
     * Expand a CIDR into the host addresses of the /24 around the phone's
     * address (1 to 254, minus the phone's own). Subnets larger than /24
     * are clamped to that /24: a /16 has 65 534 hosts and probing all of
     * them is both slow and rude to anything else on the LAN. Smaller
     * subnets are swept as the same /24. /31 and /32 return nothing.
     */
    internal fun expandSubnetIpv4(cidr: String): List<String>? {
        val (ipStr, prefixStr) = cidr.split("/").let { it.getOrNull(0) to it.getOrNull(1) }
        if (ipStr == null || prefixStr == null) return null
        val prefix = prefixStr.toIntOrNull() ?: return null
        val parts = ipStr.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
            ?: return null
        if (parts.any { it !in 0..255 }) return null
        val effPrefix = maxOf(prefix, 24)
        if (effPrefix > 30) return emptyList() // /31, /32 -- nothing useful to probe
        val (a, b, c, d) = listOf(parts[0], parts[1], parts[2], parts[3])
        // For /24 the host portion is the last octet, range 1..254 excluding self.
        return (1..254)
            .filter { it != d }
            .map { "$a.$b.$c.$it" }
    }

    companion object {
        private const val TAG = "HudSubnetProbe"
        // Max simultaneous TCP connects. High enough to sweep a /24 in ~1 s,
        // low enough to leave the radio free for the parallel mDNS browse.
        private const val MAX_CONCURRENT = 64
    }
}
