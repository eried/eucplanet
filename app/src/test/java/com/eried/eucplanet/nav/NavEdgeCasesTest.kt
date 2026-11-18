package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases for Valhalla chunking, route links, route legs and the distance unit. */
class NavEdgeCasesTest {

    // --- Valhalla chunking ---

    private fun chunks(n: Int) = RoutingService.chunkForValhalla((0 until n).toList())

    @Test fun `chunk counts for common route sizes`() {
        val expected = mapOf(9 to 1, 10 to 1, 11 to 2, 19 to 2, 20 to 3, 25 to 3)
        for ((n, count) in expected) assertEquals("n=$n", count, chunks(n).size)
    }

    @Test fun `chunk boundaries are exact`() {
        assertEquals(listOf((0..9).toList(), listOf(9, 10)), chunks(11))
        assertEquals(listOf((0..9).toList(), (9..18).toList()), chunks(19))
        assertEquals(listOf((0..9).toList(), (9..18).toList(), listOf(18, 19)), chunks(20))
        assertEquals(listOf((0..9).toList(), (9..18).toList(), (18..24).toList()), chunks(25))
    }

    @Test fun `each chunk ends where the next starts and the last has two points`() {
        for (n in listOf(9, 10, 11, 19, 20, 25)) {
            val c = chunks(n)
            for (i in 1 until c.size) assertEquals("n=$n", c[i - 1].last(), c[i].first())
            assertTrue("n=$n", c.last().size >= 2)
            assertTrue("n=$n", c.all { it.size <= RoutingService.VALHALLA_MAX_LOCATIONS })
            assertEquals(0, c.first().first())
            assertEquals(n - 1, c.last().last())
        }
    }

    @Test fun `joining a single part keeps it as it is`() {
        val part = NavRoute(
            "r", emptyList(), TravelMode.CYCLING,
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.5), GeoPoint(0.0, 1.0)),
            listOf(
                Maneuver(GeoPoint(0.0, 0.0), TurnType.DEPART, "", "", 0.0),
                Maneuver(GeoPoint(0.0, 0.5), TurnType.LEFT, "", "", 40.0),
                Maneuver(GeoPoint(0.0, 1.0), TurnType.ARRIVE, "", "", 80.0),
            ),
            80.0,
        )
        val stops = listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0))
        val joined = RoutingService.joinRoutes("r", stops, TravelMode.CYCLING, listOf(part))
        assertEquals(part.geometry, joined.geometry)
        assertEquals(part.maneuvers, joined.maneuvers)
        assertEquals(80.0, joined.totalDistanceM, 1e-9)
        assertSame(stops, joined.waypoints)
    }

    // --- Distance unit ---

    @Test fun `an explicit distance unit beats the legacy flag`() {
        assertEquals("mi", Units.effectiveDistanceUnit(AppSettings(unitDistance = "mi", imperialUnits = false)))
        assertEquals("km", Units.effectiveDistanceUnit(AppSettings(unitDistance = "km", imperialUnits = true)))
    }

    @Test fun `a blank distance unit falls back to the legacy flag`() {
        assertEquals("mi", Units.effectiveDistanceUnit(AppSettings(unitDistance = "", imperialUnits = true)))
        assertEquals("km", Units.effectiveDistanceUnit(AppSettings(unitDistance = "", imperialUnits = false)))
    }

    // --- Route links ---

    @Test fun `no stops and no track gives no link`() {
        assertNull(RouteLinks.format(SharedRoute("Empty", TravelMode.CYCLING, emptyList())))
    }

    @Test fun `a single stop with a non-ASCII name round-trips`() {
        val name = "Bjørvika café ñ"
        val link = RouteLinks.format(SharedRoute("Ålesund tur", TravelMode.CYCLING, listOf(Waypoint(59.9075, 10.7566, name = name))))!!
        val back = RouteLinks.parse(link)!!
        assertEquals("Ålesund tur", back.name)
        assertEquals(1, back.stops.size)
        assertEquals(name, back.stops[0].name)
        assertEquals(59.9075, back.stops[0].lat, 1e-5)
        assertEquals(10.7566, back.stops[0].lng, 1e-5)
    }

    // --- Route legs ---

    private val a = GeoPoint(60.0, 10.0)
    private val b = GeoPoint(60.01, 10.0)
    private val c = GeoPoint(60.01, 10.02)

    private fun line(vararg pts: GeoPoint): List<GeoPoint> =
        pts.toList().zipWithNext { p, q -> (0..9).map { t -> GeoPoint(p.lat + (q.lat - p.lat) * t / 10, p.lng + (q.lng - p.lng) * t / 10) } }
            .flatten() + pts.last()

    @Test fun `legs add up to the path when the last stop is its end`() {
        val path = line(a, b, c)
        val legs = RouteLegs.metres(listOf(a, b, c), path)
        assertEquals(2, legs.size)
        assertEquals(GeoMath.polylineLengthM(path), legs.sum(), 1e-6)
    }

    @Test fun `stops off the path still give non-negative legs`() {
        val path = line(a, b, c)
        // Each stop a few hundred metres beside the path, one far behind the start.
        val off = listOf(
            GeoPoint(59.99, 9.99),
            GeoPoint(60.005, 10.005),
            GeoPoint(60.0, 10.03),
            GeoPoint(60.02, 10.01),
        )
        val legs = RouteLegs.metres(off, path)
        assertEquals(3, legs.size)
        assertTrue(legs.toString(), legs.all { it >= 0.0 && !it.isNaN() })
    }

    @Test fun `a single stop has no legs`() {
        assertEquals(emptyList<Double>(), RouteLegs.metres(listOf(a), line(a, b)))
        assertEquals(emptyList<Double>(), RouteLegs.metres(listOf(a), emptyList()))
        assertEquals(emptyList<Double>(), RouteLegs.metres(emptyList(), line(a, b)))
    }
}
