package com.eried.eucplanet.nav

import android.content.Context
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.offline.OfflineRouter
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.nav.offline.map.OfflineMapRenderer
import com.eried.eucplanet.platform.getString
import org.jetbrains.compose.resources.StringResource
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** The routing, Overpass and Open Charge Map calls, over plain HttpURLConnection as they always went. */
class AndroidNavHttp : NavHttp {
    override fun get(
        url: String,
        headers: List<Pair<String, String>>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): NavHttpReply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            reply(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun post(
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): NavHttpReply {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body) }
            return reply(conn)
        } finally {
            conn?.disconnect()
        }
    }

    private fun reply(conn: HttpURLConnection): NavHttpReply {
        val code = conn.responseCode
        if (code in 200..299) return NavHttpReply(code, conn.inputStream.bufferedReader().use(BufferedReader::readText))
        // Drain the error body so the socket can return to the pool.
        runCatching { conn.errorStream?.use { it.readBytes() } }
        return NavHttpReply(code, null)
    }
}

/** BRouter on the downloaded tiles, the offline map's addresses and the system's connection check. */
class AndroidOfflineNav(
    private val context: Context,
    private val router: OfflineRouter,
    private val tileStore: OfflineTileStore,
    private val map: OfflineMapRenderer,
) : OfflineNav {
    override fun covers(tiles: Set<String>) = tileStore.covers(tiles)

    override suspend fun route(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: RouteAvoidances,
        heading: Double?,
    ): NavRoute? = router.route(name, waypoints, mode, avoid, heading)

    override fun addressAt(lat: Double, lng: Double): String? = map.addressAt(lat, lng)

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Strings in the app's language, from the application Context as before. */
class AndroidNavStrings(private val context: Context) : NavStrings {
    override fun text(res: StringResource, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
}
