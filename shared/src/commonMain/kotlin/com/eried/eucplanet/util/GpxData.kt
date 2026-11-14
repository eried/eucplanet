package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Waypoint

/** Parsed contents of a GPX file relevant to the Navigator. */
data class GpxData(
    val name: String,
    /** Route points (`<rtept>`): stops in riding order, for a router to join. */
    val routePoints: List<Waypoint>,
    /** Standalone `<wpt>` places. Our own older files used them as stops;
     *  other apps export bookmarks here, often many and far from the ride. */
    val places: List<Waypoint>,
    /** Track geometry (`<trkpt>`): the exact line, if any. */
    val track: List<GeoPoint>
) {
    /** What the file is to the Navigator. */
    sealed interface Contents {
        /** Stops for the router, as our own saves and plain stop lists are. */
        data class Stops(val stops: List<Waypoint>) : Contents
        /** A line to ride as drawn, locked, with the file's places. */
        data class Track(val track: List<GeoPoint>, val places: List<Waypoint>) : Contents
        data object Nothing : Contents
    }

    /**
     * Route points win: a file that names its stops wants them routed (our
     * own saves carry the solved line too, as a track, and stay editable).
     * Otherwise a track is the ride, whatever places come with it. Only a
     * file with neither treats its places as stops.
     */
    fun contents(): Contents = when {
        routePoints.isNotEmpty() -> Contents.Stops(routePoints)
        track.size >= 2 -> Contents.Track(track, places)
        places.isNotEmpty() -> Contents.Stops(places)
        else -> Contents.Nothing
    }
}
