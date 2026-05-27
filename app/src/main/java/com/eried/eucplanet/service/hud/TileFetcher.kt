package com.eried.eucplanet.service.hud

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a single XYZ raster map tile from the public CDN the rest of the
 * app already uses (CartoCDN dark basemap, matches the phone's NIGHT style).
 *
 * Why proxy instead of letting the HUD hit the CDN directly: the HUD is on
 * the phone's hotspot, and the hotspot may or may not share internet. Even
 * when it does, double-NAT plus Carto's `{s}` shard-rotation interacts badly
 * with phone hotspot DNS caching. Routing through the phone gives us a stable
 * single-origin URL the HUD's tile cache can address by integer coordinates,
 * and keeps the public CDN's User-Agent honest (`eucplanet-hud-proxy/x`).
 *
 * No on-disk cache here — OkHttp's in-memory HTTP cache covers panning back
 * over already-loaded tiles, and the HUD client side also caches the bitmap.
 * Persisting tiles across rides would be useful in v0.2 (mountain rides
 * with no signal); see HUD_NOTES.md.
 */
@Singleton
class TileFetcher @Inject constructor() {
    companion object {
        private const val TAG = "TileFetcher"
        // CartoCDN dark style — same one the phone Navigator uses for night
        // mode, picked because the HUD is always dark-themed.
        private const val URL_TEMPLATE =
            "https://%s.basemaps.cartocdn.com/dark_all/%d/%d/%d.png"
        private val SHARDS = arrayOf("a", "b", "c", "d")
        private const val USER_AGENT = "eucplanet-hud-proxy/1"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun fetch(z: Int, x: Int, y: Int): ByteArray? {
        val shard = SHARDS[(x + y) % SHARDS.size]
        val url = URL_TEMPLATE.format(shard, z, x, y)
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "tile $z/$x/$y -> ${resp.code}")
                    return null
                }
                resp.body?.bytes()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tile $z/$x/$y error: ${t.message}")
            null
        }
    }
}
