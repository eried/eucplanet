package com.eried.eucplanet.nav.offline.map

import kotlin.math.cos
import kotlin.math.hypot

/**
 * A name for a spot from the downloaded map, for a stop tapped with no
 * signal: the nearest named street, with the nearest house number when one
 * is close ("Storgata 12"), else the nearest place name.
 */
object OfflineAddress {

    private const val ROAD_M = 80.0
    private const val NUMBER_M = 35.0
    private const val PLACE_M = 3000.0

    fun at(pm: PmTiles, lat: Double, lng: Double): String? {
        val road = nearest(pm, 14, lat, lng, "roads", ROAD_M) { it.str("name") }
        val number = nearest(pm, 15, lat, lng, "buildings", NUMBER_M) { it.str("addr_housenumber") }
        return when {
            road != null && number != null -> "$road $number"
            road != null -> road
            else -> nearest(pm, 14, lat, lng, "places", PLACE_M) { it.str("name") }
        }
    }

    /** The label of the closest feature in [layer] within [maxM] metres. */
    private fun nearest(
        pm: PmTiles,
        z: Int,
        lat: Double,
        lng: Double,
        layer: String,
        maxM: Double,
        label: (VectorTile.Feature) -> String?,
    ): String? {
        // Outside towns the square has no z14 or z15 tiles: use the parent.
        var tz = z
        var bytes: ByteArray? = null
        while (tz >= z - 2 && bytes == null) {
            val (bx, by) = TileMath.xy(tz, lat, lng)
            bytes = runCatching { pm.tile(tz, bx.toInt(), by.toInt()) }.getOrNull()
            if (bytes == null) tz--
        }
        if (bytes == null) return null
        val (fx, fy) = TileMath.xy(tz, lat, lng)
        val tx = fx.toInt()
        val ty = fy.toInt()
        val l = runCatching { VectorTile.decode(bytes) }.getOrNull()?.layers?.get(layer) ?: return null
        val px = (fx - tx) * l.extent
        val py = (fy - ty) * l.extent
        val metresPerUnit = 40_075_016.7 * cos(Math.toRadians(lat)) / (1 shl tz) / l.extent
        var best: String? = null
        var bestD = maxM / metresPerUnit
        for (f in l.features) {
            val name = label(f) ?: continue
            val d = distance(f, px, py)
            if (d < bestD) { bestD = d; best = name }
        }
        return best
    }

    /** Distance in tile units from a point to a feature: to its nearest
     *  segment for lines and outlines, to the point itself otherwise. */
    private fun distance(f: VectorTile.Feature, px: Double, py: Double): Double {
        var best = Double.MAX_VALUE
        for (ring in f.rings) {
            if (ring.size == 2 || f.type == VectorTile.POINT) {
                best = minOf(best, hypot(ring[0] - px, ring[1] - py))
                continue
            }
            var i = 0
            while (i + 3 < ring.size) {
                best = minOf(best, segment(px, py, ring[i].toDouble(), ring[i + 1].toDouble(), ring[i + 2].toDouble(), ring[i + 3].toDouble()))
                i += 2
            }
        }
        return best
    }

    private fun segment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
