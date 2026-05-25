package com.eried.eucplanet.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for the Navigator feature: route building (waypoints + resolved
 * geometry) and the live-guidance runtime state.
 *
 * Everything here is plain data with hand-written JSON mapping, the current
 * route is persisted as a single JSON string in [AppSettings.navCurrentRouteJson]
 * (same DataStore-blob philosophy the rest of the app's settings use) and the
 * compact nav state is what both the phone popup and the watch render from.
 */

/** A latitude/longitude pair in WGS84 degrees. */
data class GeoPoint(val lat: Double, val lng: Double)

/** A user-placed destination or intermediate stop in a route. */
data class Waypoint(
    val lat: Double,
    val lng: Double,
    /** Human-readable label (an address, a search result, or "Pin 2"). */
    val name: String = "",
    /** Custom arrival radius in metres; null falls back to the global default. */
    val radiusM: Double? = null,
    /** True once the rider has reached this stop during the current
     *  navigation. Passed stops stay in the list (rendered as struck-
     *  through, no longer green) so the rider can still see and tap
     *  back to them, but the router treats them as done. */
    val passed: Boolean = false
) {
    fun point() = GeoPoint(lat, lng)
}

/**
 * How auto-routing should connect the waypoints. [STRAIGHT] draws plain
 * straight lines between pins and needs no routing server at all, it is also
 * the fallback when a routing request fails.
 */
enum class TravelMode {
    CYCLING, DRIVING, WALKING, STRAIGHT;

    companion object {
        fun fromName(name: String?): TravelMode =
            entries.firstOrNull { it.name == name } ?: CYCLING
    }
}

/**
 * The shape of an upcoming maneuver, normalised from whatever the routing
 * backend reported. Drives which arrow the navigation popup shows.
 */
enum class TurnType {
    DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE
}

/** The big arrow the navigation popup draws (phone + watch). */
enum class ArrowDir {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, REVERSE
}

/** Maps a routed maneuver to the arrow glyph that represents it. */
fun TurnType.toArrow(): ArrowDir = when (this) {
    TurnType.DEPART, TurnType.CONTINUE, TurnType.ARRIVE, TurnType.ROUNDABOUT -> ArrowDir.STRAIGHT
    TurnType.SLIGHT_LEFT -> ArrowDir.SLIGHT_LEFT
    TurnType.LEFT -> ArrowDir.LEFT
    TurnType.SHARP_LEFT -> ArrowDir.SHARP_LEFT
    TurnType.SLIGHT_RIGHT -> ArrowDir.SLIGHT_RIGHT
    TurnType.RIGHT -> ArrowDir.RIGHT
    TurnType.SHARP_RIGHT -> ArrowDir.SHARP_RIGHT
    TurnType.UTURN -> ArrowDir.REVERSE
}

/**
 * One turn instruction along a routed path. [distanceFromStartM] is the
 * cumulative distance from the route origin to this maneuver point, so the
 * navigation engine can announce "in X meters" as the rider approaches.
 */
data class Maneuver(
    val point: GeoPoint,
    val type: TurnType,
    val instruction: String,
    val streetName: String,
    val distanceFromStartM: Double
)

/**
 * A complete navigable route: the ordered waypoints the user placed, the
 * resolved geometry (full polyline) and the turn list. [maneuvers] is empty
 * for [TravelMode.STRAIGHT] and is ignored by Treasure Hunt (which homes in on
 * [waypoints] directly).
 */
data class NavRoute(
    val name: String,
    val waypoints: List<Waypoint>,
    val travelMode: TravelMode,
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalDistanceM: Double
) {
    val isEmpty: Boolean get() = waypoints.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("travelMode", travelMode.name)
        put("totalDistanceM", totalDistanceM)
        put("waypoints", JSONArray().also { arr ->
            waypoints.forEach { w ->
                arr.put(JSONObject().apply {
                    put("lat", w.lat); put("lng", w.lng); put("name", w.name)
                    if (w.passed) put("passed", true)
                })
            }
        })
        // Geometry is stored flat [lat0,lng0,lat1,lng1,...] to keep the blob small.
        put("geometry", JSONArray().also { arr ->
            geometry.forEach { p -> arr.put(p.lat); arr.put(p.lng) }
        })
        put("maneuvers", JSONArray().also { arr ->
            maneuvers.forEach { m ->
                arr.put(JSONObject().apply {
                    put("lat", m.point.lat); put("lng", m.point.lng)
                    put("type", m.type.name)
                    put("instruction", m.instruction)
                    put("street", m.streetName)
                    put("dist", m.distanceFromStartM)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: String?): NavRoute? {
            if (json.isNullOrBlank()) return null
            return try { fromJson(JSONObject(json)) } catch (_: Exception) { null }
        }

        fun fromJson(j: JSONObject): NavRoute {
            val waypoints = j.optJSONArray("waypoints")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Waypoint(
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        name = o.optString("name"),
                        passed = o.optBoolean("passed", false)
                    )
                }
            } ?: emptyList()
            val geomArr = j.optJSONArray("geometry") ?: JSONArray()
            val geometry = ArrayList<GeoPoint>(geomArr.length() / 2)
            var i = 0
            while (i + 1 < geomArr.length()) {
                geometry.add(GeoPoint(geomArr.getDouble(i), geomArr.getDouble(i + 1)))
                i += 2
            }
            val maneuvers = j.optJSONArray("maneuvers")?.let { arr ->
                (0 until arr.length()).map { k ->
                    val o = arr.getJSONObject(k)
                    Maneuver(
                        point = GeoPoint(o.getDouble("lat"), o.getDouble("lng")),
                        type = runCatching { TurnType.valueOf(o.optString("type")) }
                            .getOrDefault(TurnType.CONTINUE),
                        instruction = o.optString("instruction"),
                        streetName = o.optString("street"),
                        distanceFromStartM = o.optDouble("dist", 0.0)
                    )
                }
            } ?: emptyList()
            return NavRoute(
                name = j.optString("name"),
                waypoints = waypoints,
                travelMode = TravelMode.fromName(j.optString("travelMode")),
                geometry = geometry,
                maneuvers = maneuvers,
                totalDistanceM = j.optDouble("totalDistanceM", 0.0)
            )
        }
    }
}

/** Which live-guidance mode the navigation engine is running. */
enum class NavMode { TURN_BY_TURN, TREASURE_HUNT }

/**
 * Treasure-hunt warmth: whether the straight-line distance to the current
 * goal is shrinking (the rider is closing in) or growing.
 */
enum class Proximity { HOT, WARM, COLD }

/**
 * Immutable snapshot the navigation popup renders from, on the phone overlay
 * and, mirrored over the Wear Data Layer, on the watch. Strings are
 * pre-formatted (and localized) by the engine so the watch needs no logic.
 */
data class NavState(
    val active: Boolean = false,
    val mode: NavMode = NavMode.TURN_BY_TURN,
    val minimized: Boolean = false,
    /** True before a travel heading has been established, popup says "start riding". */
    val waiting: Boolean = false,
    val arrow: ArrowDir = ArrowDir.STRAIGHT,
    /**
     * Goal bearing relative to the rider's heading, degrees, -180..180
     * (0 = dead ahead, positive = to the right). Used for the treasure-hunt
     * rotating arrow.
     */
    val relativeBearingDeg: Float = 0f,
    /** Main line, e.g. "Turn left" or "Goal 2 ahead". */
    val primaryText: String = "",
    /** Distance line shown at the bottom, e.g. "200 m". */
    val distanceText: String = "",
    /** Street name of the next maneuver, when known. */
    val nextStreet: String = "",
    val proximity: Proximity? = null,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    /** 1-based index of the goal/stop currently being navigated to. */
    val goalIndex: Int = 0,
    val goalCount: Int = 0,
    /** Bumped to ask the overlay to re-open the centred popup (e.g. from the
     *  dashboard navigator button). Not a cue, purely a show trigger. */
    val popupTick: Int = 0,
    /** True while the phone's centred nav popup is on screen; the watch mirror
     *  follows this so it stays transient like the phone popup. */
    val cueVisible: Boolean = false
)

/**
 * Rotation (degrees, 0 = pointing up) for the popup's arrow glyph. Turn-by-turn
 * snaps to the eight discrete [ArrowDir] angles; Treasure Hunt rotates freely
 * to the goal's heading-relative bearing. Shared by the phone overlay and the
 * watch mirror so both point the same way.
 */
fun NavState.arrowAngleDeg(): Float = when (mode) {
    NavMode.TREASURE_HUNT -> relativeBearingDeg
    NavMode.TURN_BY_TURN -> when (arrow) {
        ArrowDir.STRAIGHT -> 0f
        ArrowDir.SLIGHT_LEFT -> -45f
        ArrowDir.LEFT -> -90f
        ArrowDir.SHARP_LEFT -> -135f
        ArrowDir.SLIGHT_RIGHT -> 45f
        ArrowDir.RIGHT -> 90f
        ArrowDir.SHARP_RIGHT -> 135f
        ArrowDir.REVERSE -> 180f
    }
}
