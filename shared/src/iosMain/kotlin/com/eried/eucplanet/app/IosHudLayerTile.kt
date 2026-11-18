package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.hud.protocol.HudTiles
import com.eried.eucplanet.map.MapLayers
import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/**
 * One finished tile of a map layer for the HUD, which only draws, as
 * Android's MapTileCache.layerTile: the provider's picture with its label
 * layers already on it, the offline map where the rider prefers it or the
 * provider cannot answer, an empty array where the provider has nothing this
 * deep, or null. Blocking; called from the tile listener's own coroutines.
 */
internal object IosHudLayerTile {
    private const val OFFLINE_PIXELS = 256
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val USER_AGENT = "EUCPlanet-Maps/1.0 (github.com/eried/eucplanet)"

    private val http by lazy { HttpClient(connectTimeoutMs = 5_000L, readTimeoutMs = 7_000L) }

    fun png(layerId: String, z: Int, x: Int, y: Int): ByteArray? {
        val prefer = IosAppGraph.settings.current.value.offlineRouting.mode == OfflineRoutingMode.PREFER
        fun offline() = IosOfflineMap.png(z, x, y, OFFLINE_PIXELS)
        if (prefer) offline()?.let { return it }
        val base = fetch(MapLayers.tileUrl(layerId, z, x, y))
            ?: return if (prefer) null else offline()
        if (HudTiles.isNoDataTile(base)) return ByteArray(0)
        val layer = MapLayers.byId(layerId)
        val labels = listOf(layer.refUrlTemplate, layer.extraRefUrlTemplate)
            .filter { it.isNotBlank() }
            .map { it.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString()) }
        if (labels.isEmpty()) return base
        return runCatching {
            val picture = Image.makeFromEncoded(base)
            val surface = Surface.makeRasterN32Premul(picture.width, picture.height)
            surface.canvas.drawImage(picture, 0f, 0f)
            // A label layer that fails still leaves the base picture.
            for (url in labels) {
                fetch(url)?.let { runCatching { surface.canvas.drawImage(Image.makeFromEncoded(it), 0f, 0f) } }
            }
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG, 100)?.bytes
                .also { surface.close() }
        }.getOrNull() ?: base
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val response = http.execute(
            HttpRequest(url, HttpRequest.Body.None, headers = listOf("User-Agent" to USER_AGENT)),
        )
        response.body.takeIf { response.isSuccessful && it.isNotEmpty() && it.size <= MAX_BYTES }
    }.getOrNull()
}
