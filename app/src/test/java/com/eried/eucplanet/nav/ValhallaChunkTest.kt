package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FOSSGIS Valhalla server answers "Exceeded max locations: 10", so a
 * route with avoidances and ten or more stops quietly lost its avoidances to
 * the OSRM fallback. Longer routes now go in stretches that share their ends.
 */
class ValhallaChunkTest {

    @Test fun `up to ten points is one request`() {
        assertEquals(listOf((0 until 10).toList()), RoutingService.chunkForValhalla((0 until 10).toList()))
    }

    @Test fun `longer routes split into stretches that share their end points`() {
        for (n in listOf(11, 19, 20, 51)) {
            val chunks = RoutingService.chunkForValhalla((0 until n).toList())
            assertTrue(chunks.all { it.size in 2..RoutingService.VALHALLA_MAX_LOCATIONS })
            for (i in 1 until chunks.size) assertEquals(chunks[i - 1].last(), chunks[i].first())
            assertEquals((0 until n).toList(), chunks.first() + chunks.drop(1).flatMap { it.drop(1) })
        }
    }

    @Test fun `joined stretches depart once, arrive once and count distance from the start`() {
        fun part(from: Double, to: Double, len: Double) = NavRoute(
            "r", emptyList(), TravelMode.CYCLING,
            listOf(GeoPoint(0.0, from), GeoPoint(0.0, (from + to) / 2), GeoPoint(0.0, to)),
            listOf(
                Maneuver(GeoPoint(0.0, from), TurnType.DEPART, "", "", 0.0),
                Maneuver(GeoPoint(0.0, (from + to) / 2), TurnType.LEFT, "", "", len / 2),
                Maneuver(GeoPoint(0.0, to), TurnType.ARRIVE, "", "", len),
            ),
            len,
        )
        val joined = RoutingService.joinRoutes("r", listOf(Waypoint(0.0, 0.0)), TravelMode.CYCLING,
            listOf(part(0.0, 1.0, 100.0), part(1.0, 2.0, 50.0)))
        assertEquals(150.0, joined.totalDistanceM, 1e-9)
        assertEquals(5, joined.geometry.size) // the shared point once
        assertEquals(listOf(TurnType.DEPART, TurnType.LEFT, TurnType.LEFT, TurnType.ARRIVE), joined.maneuvers.map { it.type })
        assertEquals(listOf(0.0, 50.0, 125.0, 150.0), joined.maneuvers.map { it.distanceFromStartM })
    }
}
