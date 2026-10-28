package com.eried.eucplanet.nav.offline

import btools.router.VoiceHint
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.GeoMath
import com.eried.eucplanet.nav.RouteAvoidances

/** Turns BRouter's output into the [NavRoute] the online routers give. */
object BrouterMapping {

    /** One BRouter turn hint: its command, the track point it sits on, and
     *  the roundabout exit (0 when not a roundabout). */
    data class Hint(val cmd: Int, val index: Int, val exit: Int)

    fun turnType(cmd: Int): TurnType = when (cmd) {
        VoiceHint.TL -> TurnType.LEFT
        VoiceHint.TSLL, VoiceHint.KL, VoiceHint.EL -> TurnType.SLIGHT_LEFT
        VoiceHint.TSHL -> TurnType.SHARP_LEFT
        VoiceHint.TR -> TurnType.RIGHT
        VoiceHint.TSLR, VoiceHint.KR, VoiceHint.ER -> TurnType.SLIGHT_RIGHT
        VoiceHint.TSHR -> TurnType.SHARP_RIGHT
        VoiceHint.TU, VoiceHint.TLU, VoiceHint.TRU -> TurnType.UTURN
        VoiceHint.RNDB, VoiceHint.RNLB -> TurnType.ROUNDABOUT
        else -> TurnType.CONTINUE
    }

    /** The profile for a travel mode; null for modes never routed offline. */
    fun profileFor(mode: TravelMode): String? = when (mode) {
        TravelMode.CYCLING -> "wheel"
        TravelMode.WALKING -> "hiking-mountain"
        TravelMode.DRIVING, TravelMode.STRAIGHT -> null
    }

    /** The rider's avoids as profile switches. Tolls have no tag in the
     *  routing data; motorways are never used by the wheel profile. */
    fun avoidParams(avoid: RouteAvoidances): Map<String, String> = buildMap {
        if (avoid.highways) put("avoid_unsafe", "true")
        if (avoid.ferries) put("allow_ferries", "false")
        if (avoid.unpaved) put("avoid_unpaved", "true")
    }

    /** BRouter's integer coordinates: microdegrees shifted to be positive. */
    fun toBrouter(p: GeoPoint): Pair<Int, Int> =
        ((p.lng + 180.0) * 1_000_000.0 + 0.5).toInt() to ((p.lat + 90.0) * 1_000_000.0 + 0.5).toInt()

    fun fromBrouter(ilon: Int, ilat: Int): GeoPoint =
        GeoPoint(ilat / 1_000_000.0 - 90.0, ilon / 1_000_000.0 - 180.0)

    /** A route from BRouter's track [points] and [hints]. Offline cues carry
     *  no street names: the routing data has none. */
    fun toNavRoute(
        name: String,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        points: List<GeoPoint>,
        hints: List<Hint>,
        distanceM: Double,
    ): NavRoute {
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + GeoMath.distanceM(points[i - 1], points[i])
        val maneuvers = ArrayList<Maneuver>()
        if (points.isNotEmpty()) maneuvers += Maneuver(points.first(), TurnType.DEPART, "", "", 0.0)
        hints.filter { it.index in 1 until points.size - 1 }.sortedBy { it.index }.forEach { h ->
            maneuvers += Maneuver(points[h.index], turnType(h.cmd), "", "", along[h.index])
        }
        if (points.size >= 2) maneuvers += Maneuver(points.last(), TurnType.ARRIVE, "", "", along.last())
        val total = if (distanceM > 0) distanceM else along.lastOrNull() ?: 0.0
        return NavRoute(name, waypoints, mode, points, maneuvers, total)
    }
}
