package com.eried.eucplanet.nav.offline.map

import com.eried.eucplanet.nav.offline.RoutingTiles
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web map tile numbers against latitude and longitude. */
object TileMath {

    fun lng(z: Int, x: Double): Double = x / (1 shl z) * 360.0 - 180.0

    fun lat(z: Int, y: Double): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** Latitude and longitude of the tile's middle. */
    fun center(z: Int, x: Int, y: Int): Pair<Double, Double> = lat(z, y + 0.5) to lng(z, x + 0.5)

    /** Tile x and y (fractional) of a point at [z]. */
    fun xy(z: Int, lat: Double, lng: Double): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (lng + 180.0) / 360.0 * n to (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n
    }

    /** The 5 degree squares a tile overlaps, middle one first. */
    fun squaresUnder(z: Int, x: Int, y: Int): List<String> {
        val (cLat, cLng) = center(z, x, y)
        val north = lat(z, y.toDouble()).coerceAtMost(89.9)
        val south = lat(z, y + 1.0).coerceAtLeast(-89.9)
        val west = lng(z, x.toDouble())
        val east = lng(z, x + 1.0) - 1e-9
        val out = linkedSetOf(RoutingTiles.nameFor(cLat, cLng))
        var la = floor(south / RoutingTiles.SIZE_DEG) * RoutingTiles.SIZE_DEG
        while (la < north) {
            var lo = floor(west / RoutingTiles.SIZE_DEG) * RoutingTiles.SIZE_DEG
            while (lo < east) {
                out += RoutingTiles.nameFor(la, lo)
                lo += RoutingTiles.SIZE_DEG
            }
            la += RoutingTiles.SIZE_DEG
        }
        return out.toList()
    }
}
