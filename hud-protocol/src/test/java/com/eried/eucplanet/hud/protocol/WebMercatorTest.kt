package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WebMercatorTest {
    @Test
    fun oppositeLongitudesNearDateLineUseAdjacentWorldCopies() {
        val east = WebMercator.tileX(179.9, 10)
        val west = WebMercator.tileX(-179.9, 10)
        val nearestWest = WebMercator.nearestWorldX(west, east, 10)

        assertTrue(abs(nearestWest - east) < 2.0)
        assertTrue(abs(nearestWest - west) > 1_000.0)
    }

    @Test
    fun negativeTileXWrapsToTheLastTile() {
        assertEquals((1 shl 10) - 1, WebMercator.wrapX(-1, 10))
        assertEquals((1 shl 10) - 1, WebMercator.wrapX(-1025, 10))
    }

    @Test
    fun extremeLatitudesProduceFiniteInRangeY() {
        val world = (1 shl 19).toDouble()
        for (latitude in listOf(-Double.MAX_VALUE, -90.0, 0.0, 90.0, Double.MAX_VALUE)) {
            val y = WebMercator.tileY(latitude, 19)
            assertTrue(y.isFinite())
            assertTrue(y >= 0.0)
            assertTrue(y < world)
        }
    }
}
