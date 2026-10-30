package com.eried.eucplanet.nav.offline.map

import kotlin.math.pow

/**
 * How the offline map looks: the OpenStreetMap street map's palette, so it
 * reads like the online map it stands in for. These are map colours, like
 * the pixels of a downloaded tile, not app colours, so they do not follow
 * the app theme.
 */
object OfflineMapStyle {

    const val WATER = 0xFFAAD3DF.toInt()
    const val LAND = 0xFFF2EFE9.toInt()
    const val BUILDING = 0xFFD9D0C9.toInt()
    const val BUILDING_EDGE = 0xFFC4B6AB.toInt()
    const val CASING = 0xFFBBB3A8.toInt()
    const val RAIL = 0xFF707070.toInt()
    const val FERRY = 0xFF6699CC.toInt()
    const val TEXT = 0xFF333333.toInt()
    const val WATER_TEXT = 0xFF4D80B3.toInt()
    const val HALO = 0xFFFFFFFF.toInt()
    const val NUMBER = 0xFF8A7F77.toInt()

    /** Fill for a landuse kind, or null to leave the land showing. */
    fun landuse(kind: String?): Int? = when (kind) {
        "forest", "wood" -> 0xFFADD19E
        "park", "garden", "nature_reserve_park", "playground", "dog_park" -> 0xFFC8FACC
        "grass", "meadow", "grassland", "village_green", "recreation_ground" -> 0xFFCDEBB0
        "scrub", "heath" -> 0xFFC8D7AB
        "wetland", "marsh", "swamp", "bog" -> 0xFFD6E3C8
        "farmland", "farmyard", "orchard", "vineyard", "allotments" -> 0xFFEEF0D5
        "residential" -> 0xFFE0DFDF
        "industrial", "railway", "quarry" -> 0xFFEBDBE8
        "commercial", "retail" -> 0xFFF2DAD9
        "cemetery", "grave_yard" -> 0xFFAACBAF
        "school", "kindergarten", "university", "college", "hospital" -> 0xFFFFFFE5
        "glacier" -> 0xFFDDECEC
        "sand", "beach" -> 0xFFF5E9C6
        "bare_rock", "scree" -> 0xFFEEE5DC
        "pitch", "golf_course", "stadium", "sports_centre" -> 0xFFAAE0CB
        "pedestrian", "platform", "pier" -> 0xFFDDDDE8
        "aerodrome", "runway", "taxiway" -> 0xFFE9E7E2
        else -> null
    }?.toInt()

    /** A road's look at a zoom. Widths are in pixels on a 256 px tile. */
    data class Line(val color: Int, val width: Float, val casing: Int? = null, val dash: Boolean = false, val order: Int = 0)

    fun road(kind: String?, detail: String?, z: Int): Line? {
        val scale = 2.0.pow((z - 15).toDouble()).toFloat()
        fun w(at15: Float, min: Float) = (at15 * scale).coerceAtLeast(min)
        return when (kind) {
            "highway" -> Line(0xFFE892A2.toInt(), w(9f, 1.5f), 0xFFDC2A67.toInt(), order = 9)
            "major_road" -> when (detail) {
                "trunk" -> Line(0xFFF9B29C.toInt(), w(9f, 1.4f), 0xFFC84E2F.toInt(), order = 8)
                "primary" -> Line(0xFFFCD6A4.toInt(), w(8.5f, 1.2f), 0xFFA06B00.toInt(), order = 7)
                "secondary" -> Line(0xFFF7FABF.toInt(), w(8f, 1.1f), 0xFF707D05.toInt(), order = 6)
                else -> Line(0xFFFFFFFF.toInt(), w(7f, 1f), CASING, order = 5)
            }
            "minor_road" -> if (z < 12) null else when (detail) {
                "service" -> Line(0xFFFFFFFF.toInt(), w(3.5f, 0.6f), CASING, order = 3)
                else -> Line(0xFFFFFFFF.toInt(), w(6f, 0.8f), CASING, order = 4)
            }
            "path" -> if (z < 13) null else when (detail) {
                "cycleway" -> Line(0xFF0000FF.toInt(), w(1.6f, 0.8f).coerceAtMost(3f), dash = true, order = 2)
                "track" -> Line(0xFF996600.toInt(), w(1.8f, 0.8f).coerceAtMost(3f), dash = true, order = 2)
                "pier" -> Line(0xFFFFFFFF.toInt(), w(4f, 1f), CASING, order = 2)
                // Sidewalks and crossings trace every street: only up close.
                "sidewalk", "crossing" -> if (z < 17) null else Line(0xFFFA8072.toInt(), 0.9f, dash = true, order = 2)
                else -> Line(0xFFFA8072.toInt(), w(1.2f, 0.7f).coerceAtMost(2f), dash = true, order = 2)
            }
            "rail" -> if (z < 10) null else Line(RAIL, w(2f, 0.8f).coerceAtMost(3f), order = 1)
            "ferry" -> Line(FERRY, 1f, dash = true, order = 1)
            "aeroway" -> Line(0xFFBBBBCC.toInt(), w(10f, 1f), order = 1)
            else -> null
        }
    }

    /** Width of a river or stream line in the water layer. */
    fun waterLine(kind: String?, z: Int): Float? {
        val scale = 2.0.pow((z - 15).toDouble()).toFloat()
        return when (kind) {
            "river", "canal" -> (10f * scale).coerceIn(1f, 30f)
            "stream", "ditch", "drain" -> if (z < 13) null else (3f * scale).coerceIn(0.7f, 8f)
            else -> null
        }
    }

    /** Smallest zoom a place name shows at. */
    fun placeMinZoom(kind: String?, detail: String?): Int = when (detail ?: kind) {
        "city" -> 6
        "town" -> 9
        "village", "suburb" -> 12
        "hamlet", "quarter", "neighbourhood" -> 14
        else -> 15
    }

    /**
     * The last zoom each layer is stored at in a square. Deeper tiles draw
     * that layer from the parent, scaled up, which is how a square stays a
     * fraction of the full map's size. Matches LAST in the eucplanet-maps
     * map script.
     */
    val LAST_ZOOM = mapOf(
        "earth" to 13, "water" to 13, "landuse" to 10, "boundaries" to 10,
        "roads" to 14, "pois" to 14, "places" to 14, "buildings" to 15,
    )

    /** Layers in drawing order. */
    val LAYERS = listOf("earth", "landuse", "water", "buildings", "roads", "places")
}
