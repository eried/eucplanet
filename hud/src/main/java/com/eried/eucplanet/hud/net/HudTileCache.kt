package com.eried.eucplanet.hud.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
 * Talks directly to a CartoCDN raster basemap. The HUD has its own wifi
 * adapter and is generally on the rider's hotspot (or an external AP),
 * so it hits the CDN directly without routing through the phone.
 *
 * Style is picked by the rider on the phone side and shipped over the
 * wire as [com.eried.eucplanet.hud.protocol.HudState.hudMapStyle]; the
 * HUD's [com.eried.eucplanet.hud.HudActivity] forwards new values to
 * [applyStyle] here. When the style changes we clear the cache and
 * cancel in-flight fetches so the rider sees the new style within a
 * few seconds instead of waiting for tiles to age out.
 *
 * LRU keeps memory bounded: 64 tiles × ~30 KB each ≈ 2 MB. Tiles
 * outside the cache fall through to the placeholder in
 * [com.eried.eucplanet.hud.ui.screens.MapScreen] until they load.
 */
class HudTileCache {

    companion object {
        private val SHARDS = arrayOf("a", "b", "c", "d")
        private const val USER_AGENT = "eucplanet-hud/1"

        /** Default style when the rider hasn't picked one yet. Voyager
         *  reads as a neutral parchment chart -- more contrast than
         *  light_all, more legible than dark_matter on the prism in low
         *  ambient light. */
        const val DEFAULT_STYLE = "voyager"

        /** Map a stored style code to the CartoCDN raster path slug.
         *  The stored code IS the slug (e.g. "light_all", "dark_nolabels",
         *  "voyager") -- we just prefix the voyager-family with the
         *  "rastertiles/" path the CDN puts them under. A small set of
         *  legacy aliases ("positron", "dark_matter", "light_all" without
         *  prefix) are still accepted so older saved AppSettings keep
         *  rendering after the rename. Unknown / empty codes fall through
         *  to [DEFAULT_STYLE]. */
        private fun pathFor(code: String): String = when (code) {
            "" -> "rastertiles/voyager"
            // Voyager family lives under rastertiles/ on the CDN.
            "voyager",
            "voyager_nolabels",
            "voyager_labels_under",
            "voyager_only_labels" -> "rastertiles/$code"
            // Carto's flat-slug families: light_* (Positron) and dark_*
            // (Dark Matter). Pass straight through.
            "light_all",
            "light_nolabels",
            "light_only_labels",
            "dark_all",
            "dark_nolabels",
            "dark_only_labels" -> code
            // Legacy aliases shipped before this rename.
            "positron" -> "light_all"
            "dark_matter" -> "dark_all"
            "dark_matter_nolabels" -> "dark_nolabels"
            else -> "rastertiles/voyager"
        }
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

    /** Currently active CartoCDN path slug, used to build per-tile URLs. */
    @Volatile
    private var stylePath: String = pathFor(DEFAULT_STYLE)

    /** Compose-observable version counter bumped on every style swap. Map
     *  callers key their LaunchedEffect on this so a style change triggers
     *  a fresh batch of [requestTile] calls (the LRU has just been cleared,
     *  so peek will return null until tiles refetch). */
    private var _styleVersionState by mutableIntStateOf(0)
    val styleVersion: Int get() = _styleVersionState

    /** Hand a freshly received [HudState.hudMapStyle] to the cache. When
     *  the resolved path changes (rider switched style on the phone),
     *  we clear the bitmap cache so the next frame's tile lookups all
     *  miss and trigger refetches in the new style. */
    fun applyStyle(code: String) {
        val newPath = pathFor(code)
        if (newPath == stylePath) return
        stylePath = newPath
        cache.evictAll()
        inflight.clear()
        _styleVersionState++
    }

    fun peek(z: Int, x: Int, y: Int): Bitmap? = cache.get(key(z, x, y))

    /** Queue a tile fetch if not already cached or in flight. [onLoaded] is
     *  invoked on the IO dispatcher after the bitmap lands in the cache, so
     *  the Compose caller can trigger a recomposition (a tick counter is
     *  enough; see MapScreen). */
    fun requestTile(z: Int, x: Int, y: Int, onLoaded: () -> Unit) {
        val k = key(z, x, y)
        if (cache.get(k) != null) return
        if (!inflight.add(k)) return
        val pathAtStart = stylePath
        scope.launch {
            try {
                val shard = SHARDS[(x + y) % SHARDS.size]
                val url = "https://$shard.basemaps.cartocdn.com/$pathAtStart/$z/$x/$y.png"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    val bytes = resp.body?.bytes() ?: return@launch
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                    // If the rider switched styles while we were
                    // mid-fetch, drop the late tile on the floor instead
                    // of poisoning the new-style cache.
                    if (pathAtStart == stylePath) {
                        cache.put(k, bmp)
                        onLoaded()
                    }
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
