package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The iPhone writes its GPX with the shared GpxText; a file saved on one phone
 * must be the same bytes the other would save, so GpxIO.write is the reference.
 */
class GpxTextParityTest {

    private fun androidBytes(route: NavRoute): String =
        ByteArrayOutputStream().also { GpxIO.write(route, it) }.toString(Charsets.UTF_8.name())

    @Test
    fun aRouteOfStops_isWrittenAsGpxIOWritesIt() {
        val route = NavRoute(
            name = "Oslo & <fjord> \"loop\"",
            waypoints = listOf(
                Waypoint(59.913868, 10.752245, "Start"),
                Waypoint(59.9075, 10.7602, ""),
                Waypoint(59.92, 10.74, "End", 35.5),
            ),
            travelMode = TravelMode.CYCLING,
            geometry = listOf(GeoPoint(59.913868, 10.752245), GeoPoint(59.92, 10.74)),
            maneuvers = emptyList(),
            totalDistanceM = 1234.0,
        )
        assertEquals(androidBytes(route), GpxText.write(route))
    }

    @Test
    fun aLockedTrack_isWrittenAsGpxIOWritesIt() {
        val line = listOf(GeoPoint(59.0, 10.0), GeoPoint(59.1, 10.1), GeoPoint(59.2, 10.2))
        val route = NavRoute(
            name = "Track",
            waypoints = listOf(Waypoint(59.0, 10.0, "a"), Waypoint(59.1, 10.1, "Cafe"), Waypoint(59.2, 10.2, "b")),
            travelMode = TravelMode.CYCLING,
            geometry = line,
            maneuvers = emptyList(),
            totalDistanceM = 1.0,
            track = line,
        )
        assertEquals(androidBytes(route), GpxText.write(route))
    }

    @Test
    fun anEmptyRoute_isWrittenAsGpxIOWritesIt() {
        val route = NavRoute("Empty", emptyList(), TravelMode.CYCLING, emptyList(), emptyList(), 0.0)
        assertEquals(androidBytes(route), GpxText.write(route))
    }
}
