package com.eried.eucplanet.hud.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.eried.eucplanet.hud.protocol.MapLayers
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
 * Talks directly to the tile provider of the rider's layer, from the
 * [MapLayers] registry every map surface shares. The HUD has its own wifi
 * adapter and is generally on the rider's hotspot (or an external AP),
 * so it hits the provider directly without routing through the phone.
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
        private const val USER_AGENT = "eucplanet-hud/1"

        /** What the HUD draws before the phone has said which layer the rider
         *  picked: the same light chart the phone's picker defaults to. */
        const val DEFAULT_STYLE = MapLayers.LIGHT
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

    /** The layer whose tiles we fetch. Resolved through [MapLayers.byId], so
     *  the phone's seven picker codes and the CARTO slugs older phones still
     *  send all land on a real entry instead of one hardcoded chart. */
    @Volatile
    private var layer: MapLayers.Layer = MapLayers.byId(DEFAULT_STYLE)

    /** How deep the current provider renders. Esri's Canvas charts stop at
     *  16 and answer deeper requests with a "Map data not yet available"
     *  tile, so a map screen must fetch at most this zoom and scale up. */
    val maxNativeZoom: Int get() = layer.maxNativeZoom

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
        val next = MapLayers.byId(code.ifBlank { DEFAULT_STYLE })
        if (next.id == layer.id) return
        layer = next
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
        val layerAtStart = layer
        scope.launch {
            try {
                val url = MapLayers.tileUrl(layerAtStart.id, z, x, y)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    val bytes = resp.body?.bytes() ?: return@launch
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                    // Esri Canvas keeps its labels on a separate reference
                    // layer named by the base URL; composite it on top. A
                    // failed label fetch still shows the base tile.
                    val refUrl = MapLayers.refTileUrl(layerAtStart.id, z, x, y)
                    if (refUrl != null) {
                        runCatching {
                            val refReq = Request.Builder()
                                .url(refUrl)
                                .header("User-Agent", USER_AGENT)
                                .build()
                            client.newCall(refReq).execute().use { refResp ->
                                if (refResp.isSuccessful) {
                                    refResp.body?.bytes()?.let { rb ->
                                        BitmapFactory.decodeByteArray(rb, 0, rb.size)?.let { ref ->
                                            val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                                            android.graphics.Canvas(out).drawBitmap(ref, 0f, 0f, null)
                                            bmp = out
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // If the rider switched styles while we were
                    // mid-fetch, drop the late tile on the floor instead
                    // of poisoning the new-style cache.
                    if (layerAtStart.id == layer.id) {
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
