package com.eried.eucplanet.hud.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HUD-side tile fetch + cache.
 *
 * Talks directly to the public CartoCDN dark basemap -- same one the phone
 * Navigator uses for night mode, picked because the HUD is always
 * dark-themed. The HUD has its own wifi adapter and is generally on the
 * rider's hotspot (or an external AP), so it can hit the CDN without
 * routing through the phone.
 *
 * LRU keeps memory bounded: 64 tiles × ~30 KB each = ~2 MB. Tiles outside
 * the cache fall through to the "checkerboard" placeholder in
 * [com.eried.eucplanet.hud.ui.screens.MapScreen] until they load.
 */
class HudTileCache {

    companion object {
        // CartoCDN's "voyager" raster style: neutral parchment / light-
        // grey background with the road network in contrasting blues
        // and labels in a darker grey-brown. Rider asked for greater
        // contrast and a more neutral palette than light_all (too white,
        // too saturated on the prism in low ambient light) and brighter
        // than dark_matter (whose dark background blends into the HUD's
        // black-is-transparent prism at low brightness, making the
        // entire chart vanish).
        private const val URL_TEMPLATE =
            "https://%s.basemaps.cartocdn.com/rastertiles/voyager/%d/%d/%d.png"
        private val SHARDS = arrayOf("a", "b", "c", "d")
        private const val USER_AGENT = "eucplanet-hud/1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    /** Tiles currently being fetched, keyed by the canonical "z/x/y" string.
     *  Prevents the per-frame requestTile call from spawning duplicate fetches. */
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun peek(z: Int, x: Int, y: Int): Bitmap? = cache.get(key(z, x, y))

    /** Queue a tile fetch if not already cached or in flight. [onLoaded] is
     *  invoked on the IO dispatcher after the bitmap lands in the cache, so
     *  the Compose caller can trigger a recomposition (a tick counter is
     *  enough; see MapScreen). */
    fun requestTile(z: Int, x: Int, y: Int, onLoaded: () -> Unit) {
        val k = key(z, x, y)
        if (cache.get(k) != null) return
        if (!inflight.add(k)) return
        scope.launch {
            try {
                val shard = SHARDS[(x + y) % SHARDS.size]
                val url = URL_TEMPLATE.format(shard, z, x, y)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    val bytes = resp.body?.bytes() ?: return@launch
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                    cache.put(k, bmp)
                    onLoaded()
                }
            } catch (_: Throwable) {
                // Network blip; we'll retry on the next viewport-change pass.
            } finally {
                inflight.remove(k)
            }
        }
    }

    private fun key(z: Int, x: Int, y: Int): String = "$z/$x/$y"
}
