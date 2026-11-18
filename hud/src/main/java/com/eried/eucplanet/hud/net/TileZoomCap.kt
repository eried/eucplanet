package com.eried.eucplanet.hud.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest

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
        private const val NO_DATA_SIZE = 2521
        private const val NO_DATA_MD5 = "f27d9de7f80c13501f470595e327aa6d"

        fun isNoDataTile(bytes: ByteArray): Boolean {
            if (bytes.size != NO_DATA_SIZE) return false
            val md5 = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return md5 == NO_DATA_MD5
        }
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
