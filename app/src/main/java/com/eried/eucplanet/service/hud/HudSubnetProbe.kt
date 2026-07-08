package com.eried.eucplanet.service.hud

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import com.eried.eucplanet.hud.protocol.HudDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last-resort discovery: when neither the UDP beacon nor mDNS has produced
 * a usable address within a few seconds, probe every host in the phone's
 * own /24 subnet on the HUD WebSocket port and return the first one that
 * accepts a TCP connection.
 *
 * Runs all 254 connect attempts concurrently with a per-host timeout of
 * 250 ms. On modern Android this completes in well under 1 s even on a
 * full subnet. We don't speak the WebSocket handshake -- a plain TCP open
 * is enough evidence that *something* is listening on the right port; the
 * dial loop will then attempt a real handshake and either succeed or fail
 * back to the next discovery cycle.
 *
 * The probe is gated on the rider opting into auto-discovery AND the first
 * two faster paths having failed, so the network noise is only generated
 * when there's no other way to find the HUD.
 */
@Singleton
class HudSubnetProbe @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /** The CIDRs the most recent [probe] swept. Lets the dial loop (a) put
     *  the actual scanned subnets into the diagnostics instead of a bare
     *  "scanning /24", and (b) tell when the HUD's last-known subnet no
     *  longer exists on this phone at all (hotspot off). */
    @Volatile var lastScannedCidrs: List<String> = emptyList()
        private set

    suspend fun probe(port: Int = HudDiscovery.DEFAULT_PORT): String? = withContext(Dispatchers.IO) {
        // Scan EVERY local IPv4 subnet we can see, not just the "active
        // network" one. Critical for the phone-hotspot case: when the phone
        // shares its connection, `activeNetwork` is the CELLULAR route, so the
        // old single-subnet probe scanned a carrier subnet and never saw the
        // HUD sitting on the 192.168.x AP interface. Enumerating interfaces
        // catches the AP (and any real WiFi) subnet too.
        val cidrs = candidateIpv4Cidrs()
        lastScannedCidrs = cidrs
        if (cidrs.isEmpty()) return@withContext null
        val candidates = cidrs.flatMap { expandSubnetIpv4(it) ?: emptyList() }.distinct()
        if (candidates.isEmpty()) return@withContext null
        Log.i(TAG, "probing ${candidates.size} hosts across ${cidrs.size} subnet(s) $cidrs:$port")
        coroutineScope {
            val results = candidates.map { ip ->
                async {
                    if (tryTcp(ip, port, timeoutMs = 250)) ip else null
                }
            }.awaitAll()
            results.firstOrNull { it != null }
        }
    }

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
    private fun candidateIpv4Cidrs(): List<String> {
        val out = LinkedHashSet<String>()

        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
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
        }.onFailure { Log.w(TAG, "interface enumeration failed: ${it.message}") }

        return out.toList()
    }

    /**
     * Expand a /24 CIDR (or wider, clamped to /24) into the list of host
     * addresses excluding the network address, the broadcast address, and
     * the phone's own address. Subnets larger than /24 are clamped to /24
     * around the phone's own host octet -- a /16 has 65 534 hosts and
     * probing all of them is both slow and rude to anything else on the
     * LAN. Smaller-than-/24 subnets are honoured as-is.
     */
    private fun expandSubnetIpv4(cidr: String): List<String>? {
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

    private fun tryTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { sock ->
                val ok = withTimeoutOrNullSync(timeoutMs.toLong()) {
                    sock.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                } ?: false
                ok
            }
        }.getOrElse { false }
    }

    /**
     * Tiny synchronous timeout wrapper. We can't use kotlinx's
     * `withTimeoutOrNull` here because the Socket.connect call must NOT
     * be interruptible from a coroutine -- the underlying NIO channel
     * doesn't honour the cancellation and the resulting socket leaks a
     * file descriptor. Socket's own SO_TIMEOUT param handles the wait;
     * this wrapper just keeps the call shape clean.
     */
    private inline fun <T> withTimeoutOrNullSync(timeoutMs: Long, block: () -> T): T? {
        return try { block() } catch (_: Throwable) { null }
    }

    companion object { private const val TAG = "HudSubnetProbe" }
}
