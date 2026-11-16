package com.eried.eucplanet.service.hud

import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/*
 * What the HUD link needs from the phone it runs on. The rules (which
 * address to dial, when to retry, what a frame carries, what a command does)
 * are in HudServer and HudCommandSink, once. These are the sockets and
 * radios under them:
 *
 * - Android: OkHttp, JmDNS, ConnectivityManager and java.net, as before the
 *   iPhone could drive a HUD (app/.../service/hud/AndroidHudLink.kt).
 * - iPhone: NSURLSession, Bonjour and BSD sockets (iosMain IosHudLink.kt).
 */

/** One WebSocket to the HUD. */
interface HudSocket {
    /** False when the frame could not be queued, which ends the link. */
    fun send(text: String): Boolean

    fun close(code: Int, reason: String?)

    /** Fails the socket at once, with no closing handshake. */
    fun cancel()
}

/** What a [HudSocket] reports, from whatever thread the platform reads on. */
interface HudSocketEvents {
    fun onOpen(socket: HudSocket)

    fun onMessage(text: String)

    /** The HUD is hanging up. */
    fun onClosing(socket: HudSocket, code: Int, reason: String)

    fun onClosed()

    /** The dial or the open link failed. [httpCode] when the HUD answered with one. */
    fun onFailure(error: Throwable, httpCode: Int?)
}

/** The WebSocket client. [pingMs] is how long a silent HUD is given. */
fun interface HudSockets {
    fun open(url: String, pingMs: Long, events: HudSocketEvents): HudSocket
}

/** The mDNS (Bonjour) browse for [HudDiscovery.SERVICE_TYPE]. */
interface HudMdns {
    /** "ip:port" of the first HUD whose protocol this phone speaks, null when none answers. */
    suspend fun resolve(timeoutMs: Long, serviceInfoTimeoutMs: Long): String?

    /** Lets go of what the browse held (Android's multicast lock). */
    fun release()

    companion object {
        /**
         * Whether this phone speaks to a HUD announcing [txtVersion] (its
         * [HudDiscovery.TXT_VERSION] record, 1 when it carries none): any
         * major version up to the phone's own.
         */
        fun speaks(txtVersion: String?): Boolean = (txtVersion?.toIntOrNull() ?: 1) <= HudState.PROTOCOL_MAJOR
    }
}

/** A HUD's discovery beacon, as heard. */
data class HudBeaconSighting(val ip: String, val port: Int, val receivedAtMs: Long)

/**
 * The listener for the HUD's UDP beacon. The HUD sends it as a broadcast, so
 * the iPhone hears none: iOS hands broadcasts only to apps with the multicast
 * entitlement, which this app does not have.
 */
interface HudBeacons {
    val latest: StateFlow<HudBeaconSighting?>

    /** Counters for the discovery trace, so a capture shows whether beacons arrive at all. */
    val totalReceived: Long
    val lastReceiveAtMs: Long
    val lastBindError: String

    fun start()

    fun stop()
}

/** What the phone's Wi-Fi tells the link while it is on. */
interface HudNetworkEvents {
    /** A network came up. */
    fun onAvailable()

    /** The rider's own (internet) Wi-Fi joined, which on one radio re-tunes the hotspot. */
    fun onOwnWifiJoined()

    /** A network is gone. [wasOwnWifi] when it was the rider's own Wi-Fi. */
    fun onLost(wasOwnWifi: Boolean)

    /** The rider's own Wi-Fi is about to go. */
    fun onOwnWifiLeaving(msToLive: Int)
}

/** The phone's networks. */
interface HudNetwork {
    /** Every local IPv4 network the phone is on, as "ip/prefix". */
    fun candidateIpv4Cidrs(): List<String>

    /** True when something accepts a TCP connection there. Blocks for up to [timeoutMs]. */
    fun tryTcp(host: String, port: Int, timeoutMs: Int): Boolean

    fun watch(events: HudNetworkEvents)

    fun unwatch()

    /** Keeps the Wi-Fi radio out of power-save while a HUD is dialled (Android). */
    fun holdRadioAwake(hold: Boolean)
}

/** The offline map for the HUD, on [HudDiscovery.TILE_PORT] while the link is on. */
interface HudTiles {
    fun start(): Boolean

    fun stop()
}

/** The phone around the link. */
interface HudPhone {
    /** The app is on screen. */
    val foreground: StateFlow<Boolean>

    /**
     * Whether a new socket may keep the short ping window. Android throttles
     * the sockets of an app that is off screen, so it asks the process itself.
     */
    fun uiForeground(): Boolean

    /** The phone's position, null without a fresh fix. */
    fun location(): PhoneLocation?

    /** The HUD map draws from this phone's position, so GPS stays on while it shows. */
    fun setMapLinked(linked: Boolean)

    /** [res] in the rider's language, outside composition. */
    fun text(res: StringResource): String

    /** A debug switch by its Android property name, null when unset. */
    fun debug(name: String): String?

    /** The link to the HUD ended. */
    fun onLinkDown() {}
}

/** The beacon's text: `EUCPLANET-HUD v1 ip=10.80.67.125 port=28080 [...]`. */
object HudBeacon {
    /**
     * The `ip=` field is the canonical "dial this address" hint. The packet's
     * source address stays as a fallback for a HUD that did not manage to put
     * its own IP into the payload.
     */
    fun parse(text: String, sourceIp: String, nowMs: Long): HudBeaconSighting? {
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
        return HudBeaconSighting(ip = ip, port = port, receivedAtMs = nowMs)
    }
}

/** The one request the tile listener answers: `GET /tiles/z/x/y.png`. */
object HudTileHttp {
    private val PATH = Regex("""GET /tiles/(\d{1,2})/(\d{1,7})/(\d{1,7})\.png(\?\S*)? HTTP/1\.[01]""")

    /** z, x and y from a request line, or null for anything else. */
    fun parse(requestLine: String): Triple<Int, Int, Int>? {
        val m = PATH.matchEntire(requestLine.trim()) ?: return null
        val z = m.groupValues[1].toInt()
        val x = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt()
        val n = 1 shl z.coerceAtMost(30)
        return if (z <= 22 && x < n && y < n) Triple(z, x, y) else null
    }

    /** The response head for a tile of [bodySize] bytes, or the 404 for null. */
    fun head(bodySize: Int?): String =
        if (bodySize == null) "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        else "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: $bodySize\r\nConnection: close\r\n\r\n"
}
