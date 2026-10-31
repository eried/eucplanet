package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tile names must match brouter.de's files, south-west corner, 5 degrees. */
class RoutingTilesTest {

    @Test fun oslo_isE10N55() = assertEquals("E10_N55", RoutingTiles.nameFor(59.91, 10.75))

    @Test fun westAndSouth_useWAndS() = assertEquals("W5_S5", RoutingTiles.nameFor(-0.1, -0.1))

    @Test fun zeroZero_isE0N0() = assertEquals("E0_N0", RoutingTiles.nameFor(0.0, 0.0))

    @Test fun anEdge_belongsToTheTileItStarts() = assertEquals("E15_N60", RoutingTiles.nameFor(60.0, 15.0))

    @Test fun farWest_andTheDateLine() {
        assertEquals("W180_N60", RoutingTiles.nameFor(64.8, -179.9))
        assertEquals("E175_S40", RoutingTiles.nameFor(-36.8, 179.9))
    }

    @Test fun bounds_ofANamedTile() {
        assertArrayEquals(doubleArrayOf(55.0, 10.0, 60.0, 15.0), RoutingTiles.bounds("E10_N55"), 0.0)
        assertArrayEquals(doubleArrayOf(-5.0, -5.0, 0.0, 0.0), RoutingTiles.bounds("W5_S5"), 0.0)
    }

    @Test fun aLegInsideOneTile_needsOne() =
        assertEquals(setOf("E10_N55"), RoutingTiles.tilesForLeg(GeoPoint(59.91, 10.75), GeoPoint(59.74, 10.20)))

    @Test fun aLegAcrossABorder_needsBoth() {
        val t = RoutingTiles.tilesForLeg(GeoPoint(59.9, 14.9), GeoPoint(59.9, 15.2))
        assertTrue(t.containsAll(setOf("E10_N55", "E15_N55")))
    }

    @Test fun aLegNearABorder_takesThePaddedNeighbour() {
        // The road may bulge a little past the straight line.
        val t = RoutingTiles.tilesForLeg(GeoPoint(59.98, 10.75), GeoPoint(59.97, 10.80))
        assertTrue(t.contains("E10_N60"))
    }

    @Test fun legKm_osloToDrammen() {
        val km = RoutingTiles.legKm(GeoPoint(59.9139, 10.7522), GeoPoint(59.7439, 10.2045))
        assertEquals(35.0, km, 3.0)
    }
}
