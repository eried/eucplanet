package com.eried.eucplanet.nav.offline.map

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The offline map in the Leaflet maps (navigator, trip details, offline
 * areas). The page asks for `https://offline-map.eucplanet/z/x/y.png`, the
 * WebView hands that to [intercept], and the phone draws the tile. The layer
 * sits under the online map, so it only shows where online tiles fail, and
 * the credit line says so while it does.
 */
object OfflineMapWeb {

    const val HOST = "offline-map.eucplanet"
    const val NAME = OfflineMapJs.NAME

    /** Tiles are drawn at twice the size Leaflet shows them, so text stays
     *  sharp on a phone screen. */
    private const val PIXELS = 512

    /** A drawn tile for an offline map request, an empty 404 when no square
     *  has it, or null for any other request. */
    fun intercept(renderer: OfflineMapRenderer, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        val parts = url.pathSegments
        val z = parts.getOrNull(0)?.toIntOrNull()
        val x = parts.getOrNull(1)?.toIntOrNull()
        val y = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
        val png = if (z != null && x != null && y != null) runCatching { renderer.png(z, x, y, PIXELS) }.onFailure { android.util.Log.w("OfflineMap", "$z/$x/$y: $it") }.getOrNull() else null
        return if (png != null) {
            WebResourceResponse("image/png", null, 200, "OK", HEADERS, ByteArrayInputStream(png))
        } else {
            WebResourceResponse("image/png", null, 404, "Not Found", HEADERS, ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** The app's renderer, for WebViews built outside Koin's reach. */
    fun renderer(): OfflineMapRenderer = org.koin.core.context.GlobalContext.get().get()

    private val HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")

    /** "Prefer offline" is on: downloaded squares show their own map over
     *  the online one. Kept in step with the setting by [MapTileCache]. */
    @Volatile
    var prefer = false

    /** The shared script ([OfflineMapJs]) over this WebView's intercepted host. */
    val JS get() = OfflineMapJs.js("https://$HOST/{z}/{x}/{y}.png", prefer)
}
