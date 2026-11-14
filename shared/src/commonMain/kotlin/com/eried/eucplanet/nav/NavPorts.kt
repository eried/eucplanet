package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest

/**
 * One answer from [NavHttp]: the status code, and the body read as text
 * only for a 2xx reply (an error body is drained and dropped).
 */
class NavHttpReply(val code: Int, val body: String?)

/**
 * The plain HTTP the routers, geocoder, Overpass and Open Charge Map calls
 * make, blocking (callers are on Dispatchers.IO). Android keeps its
 * `HttpURLConnection` calls (AndroidNavHttp), so requests go out exactly as
 * before; [PlatformNavHttp] sends the same ones through the shared client.
 * Throws when no reply arrives at all.
 */
interface NavHttp {
    fun get(url: String, headers: List<Pair<String, String>>, connectTimeoutMs: Int, readTimeoutMs: Int): NavHttpReply

    /** A POST of [body] (its type is in [headers]). */
    fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): NavHttpReply
}

/** [NavHttp] over the shared [HttpClient] (NSURLSession on iOS). */
class PlatformNavHttp : NavHttp {
    override fun get(url: String, headers: List<Pair<String, String>>, connectTimeoutMs: Int, readTimeoutMs: Int) =
        send(HttpRequest(url, HttpRequest.Body.None, headers), connectTimeoutMs, readTimeoutMs)

    override fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): NavHttpReply {
        val type = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val rest = headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) }
        return send(HttpRequest(url, HttpRequest.Body.Bytes(type, body), rest), connectTimeoutMs, readTimeoutMs)
    }

    private fun send(request: HttpRequest, connectMs: Int, readMs: Int): NavHttpReply {
        val reply = HttpClient(connectMs.toLong(), readMs.toLong()).execute(request)
        return NavHttpReply(reply.code, if (reply.isSuccessful) reply.text() else null)
    }
}

/**
 * The phone-side half of routing: the downloaded tiles, the on-phone router,
 * the offline map's addresses and the connection check. Android has BRouter,
 * the tile store and the offline map renderer behind it (AndroidOfflineNav);
 * a phone with none of those answers "not covered" and routes online only.
 */
interface OfflineNav {
    /** True when every routing tile in [tiles] is downloaded. */
    fun covers(tiles: Set<String>): Boolean

    /** A route on the phone, or null when it cannot make one. */
    suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: RouteAvoidances,
        heading: Double?,
    ): NavRoute?

    /** The downloaded map's address at a point, or null. */
    fun addressAt(lat: Double, lng: Double): String?

    /** True when the phone has a working internet connection. */
    fun isOnline(): Boolean

    /** No tiles, no router, no map: every leg routes online. */
    object None : OfflineNav {
        override fun covers(tiles: Set<String>) = false
        override suspend fun route(
            name: String,
            waypoints: List<Waypoint>,
            mode: TravelMode,
            avoid: RouteAvoidances,
            heading: Double?,
        ): NavRoute? = null
        override fun addressAt(lat: Double, lng: Double): String? = null
        override fun isOnline() = true
    }
}

/** A shared string in the app's language, formatted like Android's getString. */
fun interface NavStrings {
    fun text(res: org.jetbrains.compose.resources.StringResource, vararg args: Any): String
}

/** [NavStrings] as the `(res, args)` lookup [NavFormat] takes. */
fun NavStrings.lookup(): (org.jetbrains.compose.resources.StringResource, List<Any>) -> String =
    { res, args -> text(res, *args.toTypedArray()) }
