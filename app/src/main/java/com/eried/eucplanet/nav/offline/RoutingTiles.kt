package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.nav.GeoMath
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * BRouter's routing tiles: 5 by 5 degree squares named by their south-west
 * corner, like `E10_N55` (Oslo) or `W5_S5`, matching the files on the server.
 */
object RoutingTiles {
    const val SIZE_DEG = 5

    /** Longest straight-line leg routed offline. BRouter's time grows with
     *  the square of the distance, so past this a phone is too slow. */
    const val MAX_LEG_KM = 100.0

    /** The tile a point is in. A point on an edge belongs to the tile that starts there. */
    fun nameFor(lat: Double, lng: Double): String {
        val lon0 = corner(lng.coerceIn(-180.0, 179.999999))
        val lat0 = corner(lat.coerceIn(-90.0, 89.999999))
        val ew = if (lon0 < 0) "W${-lon0}" else "E$lon0"
        val ns = if (lat0 < 0) "S${-lat0}" else "N$lat0"
        return "${ew}_$ns"
    }

    /** Every tile a leg from [a] to [b] may touch: the box around both ends,
     *  widened by [padDeg] because a road bulges past the straight line. */
    fun tilesForLeg(a: GeoPoint, b: GeoPoint, padDeg: Double = 0.05): Set<String> {
        val south = max(-90.0, min(a.lat, b.lat) - padDeg)
        val north = min(89.999999, max(a.lat, b.lat) + padDeg)
        val west = max(-180.0, min(a.lng, b.lng) - padDeg)
        val east = min(179.999999, max(a.lng, b.lng) + padDeg)
        val out = mutableSetOf<String>()
        var lat0 = corner(south)
        while (lat0 <= north) {
            var lon0 = corner(west)
            while (lon0 <= east) {
                out += nameFor(lat0.toDouble(), lon0.toDouble())
                lon0 += SIZE_DEG
            }
            lat0 += SIZE_DEG
        }
        return out
    }

    /** `[south, west, north, east]` of a tile name; throws for a name that is not one. */
    fun bounds(name: String): DoubleArray {
        val m = NAME.matchEntire(name) ?: throw IllegalArgumentException("not a tile: $name")
        val lon = m.groupValues[2].toInt() * if (m.groupValues[1] == "W") -1 else 1
        val lat = m.groupValues[4].toInt() * if (m.groupValues[3] == "S") -1 else 1
        return doubleArrayOf(lat.toDouble(), lon.toDouble(), (lat + SIZE_DEG).toDouble(), (lon + SIZE_DEG).toDouble())
    }

    fun isTileName(name: String): Boolean = NAME.matches(name)

    /** Straight-line length of a leg, in km. */
    fun legKm(a: GeoPoint, b: GeoPoint): Double = GeoMath.distanceM(a, b) / 1000.0

    private fun corner(deg: Double): Int = (floor(deg / SIZE_DEG) * SIZE_DEG).toInt()

    private val NAME = Regex("""([EW])(\d{1,3})_([NS])(\d{1,2})""")
}
