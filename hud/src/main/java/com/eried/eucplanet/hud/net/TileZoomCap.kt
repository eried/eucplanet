package com.eried.eucplanet.hud.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.eried.eucplanet.hud.protocol.HudTiles

/**
 * How deep the map may fetch where the rider actually is. Esri answers a
 * zoom it has no data for with a "Map data not yet available" tile instead of
 * an error, and how deep its data goes depends on the place: the Canvas
 * charts stop at 16 everywhere, satellite imagery at 18 or 19 depending on
 * the area. The layer's fixed cap cannot know that, so a no-data tile lowers
 * the cap below its zoom and the map draws the coarser tiles scaled up.
 */
class TileZoomCap {
    companion object {
        const val MIN_ZOOM = 3

        fun isNoDataTile(bytes: ByteArray): Boolean = HudTiles.isNoDataTile(bytes)
    }

    /** Lowest zoom that returned the no-data tile; Int.MAX_VALUE when none has. */
    private var noDataZoom by mutableIntStateOf(Int.MAX_VALUE)

    fun cap(layerMax: Int): Int =
        minOf(layerMax, noDataZoom - 1).coerceAtLeast(MIN_ZOOM)

    fun onNoData(z: Int) {
        if (z < noDataZoom) noDataZoom = z
    }

    fun reset() {
        noDataZoom = Int.MAX_VALUE
    }
}
