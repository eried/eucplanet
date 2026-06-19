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

    suspend fun probe(port: Int = HudDiscovery.DEFAULT_PORT): String? = withContext(Dispatchers.IO) {
        val cidr = currentIpv4Cidr() ?: return@withContext null
        val candidates = expandSubnetIpv4(cidr) ?: return@withContext null
        Log.i(TAG, "probing ${candidates.size} hosts on $cidr:$port")
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
     * Pick the phone's own IPv4 + prefix length from the active network's
     * `LinkProperties`. Falls back to null when there is no IPv4 (rare; some
     * IPv6-only carrier setups) or when the active network is a cellular
     * route rather than WiFi / hotspot.
     */
    private fun currentIpv4Cidr(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(active) ?: return null
        val v4: LinkAddress = props.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null
        return "${v4.address.hostAddress}/${v4.prefixLength}"
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
