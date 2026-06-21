package com.eried.eucplanet.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Data model for the Navigator feature: route building (waypoints + resolved
 * geometry) and the live-guidance runtime state. Faithful port of Android's
 * NavModels — the only platform change is JSON: Android used org.json, the
 * shared module uses kotlinx.serialization (the route blob is in-memory only,
 * never shared cross-platform, so any self-consistent encoding is fine).
 */

/** A latitude/longitude pair in WGS84 degrees. */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/** A user-placed destination or intermediate stop in a route. */
@Serializable
data class Waypoint(
    val lat: Double,
    val lng: Double,
    /** Human-readable label (an address, a search result, or "Pin 2"). */
    val name: String = "",
    /** Custom arrival radius in metres; null falls back to the global default. */
    val radiusM: Double? = null,
    /** True once the rider has reached this stop during the current navigation. */
    val passed: Boolean = false,
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
@Serializable
data class Maneuver(
    val point: GeoPoint,
    val type: TurnType,
    val instruction: String,
    val streetName: String,
    val distanceFromStartM: Double,
)

/**
 * A complete navigable route: the ordered waypoints the user placed, the
 * resolved geometry (full polyline) and the turn list. [maneuvers] is empty
 * for [TravelMode.STRAIGHT] and is ignored by Treasure Hunt (which homes in on
 * [waypoints] directly).
 */
@Serializable
data class NavRoute(
    val name: String,
    val waypoints: List<Waypoint>,
    val travelMode: TravelMode,
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalDistanceM: Double,
) {
    val isEmpty: Boolean get() = waypoints.isEmpty()

    fun toJson(): String = NAV_JSON.encodeToString(this)

    companion object {
        fun fromJson(json: String?): NavRoute? {
            if (json.isNullOrBlank()) return null
            return try { NAV_JSON.decodeFromString<NavRoute>(json) } catch (_: Exception) { null }
        }
    }
}

private val NAV_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
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
 * and, mirrored to the watch. Strings are pre-formatted (and localized) by the
 * engine so the watch needs no logic.
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
    /** Bumped to ask the overlay to re-open the centred popup. Not a cue, purely a show trigger. */
    val popupTick: Int = 0,
    /** True while the phone's centred nav popup is on screen; the watch mirror follows this. */
    val cueVisible: Boolean = false,
)

/**
 * Rotation (degrees, 0 = pointing up) for the popup's arrow glyph. Turn-by-turn
 * snaps to the eight discrete [ArrowDir] angles; Treasure Hunt rotates freely
 * to the goal's heading-relative bearing.
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
