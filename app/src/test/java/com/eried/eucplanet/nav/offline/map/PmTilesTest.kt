package com.eried.eucplanet.nav.offline.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A downloaded map square reads back as vector tiles. */
class PmTilesTest {

    @Test fun tileIds_followTheHilbertCurve() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
    }

    @Test fun aTileAcrossTwoSquares_isLookedUpInBoth() {
        // z9 tile 282/116 is near Tromso, inside E15_N65. z6 tile 35/14 spans 16.9 to 22.5 E.
        assertEquals(listOf("E15_N65"), TileMath.squaresUnder(9, 282, 116).take(1))
        val wide = TileMath.squaresUnder(6, 35, 14)
        assertTrue(wide.containsAll(listOf("E15_N65", "E20_N65")))
    }

    @Test fun tromso_hasRoadNamesAndHouseNumbers() {
        // Runs only when BROUTER_TILES names a folder with E15_N65.pmtiles.
        val f = System.getenv("BROUTER_TILES")?.let { File(it, "E15_N65.pmtiles") }
        assumeTrue(f != null && f.isFile)
        PmTiles(f!!).use { pm ->
            assertEquals(15, pm.maxZoom)
            val (x14, y14) = TileMath.xy(14, 69.6496, 18.9560)
            val roads = VectorTile.decode(assertNotNullAnd(pm.tile(14, x14.toInt(), y14.toInt()))).layers["roads"]
            assertTrue(roads!!.features.any { it.str("name") != null })

            val (x15, y15) = TileMath.xy(15, 69.6496, 18.9560)
            val t15 = VectorTile.decode(assertNotNullAnd(pm.tile(15, x15.toInt(), y15.toInt())))
            assertTrue(t15.layers["buildings"]!!.features.any { it.str("addr_housenumber") != null })
            // z15 keeps buildings only; roads come from z14.
            assertNull(t15.layers["roads"])
            // Outside the square: nothing.
            val (xo, yo) = TileMath.xy(15, 59.91, 10.75)
            assertNull(pm.tile(15, xo.toInt(), yo.toInt()))
        }
    }

    @Test fun aWholeMapSquare_passesTheCheck_aCutOneDoesNot() {
        val f = System.getenv("BROUTER_TILES")?.let { File(it, "E15_N65.pmtiles") }
        assumeTrue(f != null && f.isFile)
        assertNull(com.eried.eucplanet.nav.offline.MapCheck.problem(f!!))
        val cut = File.createTempFile("cut", ".pmtiles").apply { deleteOnExit() }
        f.inputStream().use { i -> cut.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            var left = f.length() / 2
            while (left > 0) { val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); o.write(buf, 0, n); left -= n }
        } }
        assertNotNull(com.eried.eucplanet.nav.offline.MapCheck.problem(cut))
        val junk = File.createTempFile("junk", ".pmtiles").apply { deleteOnExit(); writeBytes(ByteArray(500) { 7 }) }
        assertNotNull(com.eried.eucplanet.nav.offline.MapCheck.problem(junk))
    }

    @Test fun aTappedSpot_isNamedStreetAndNumber_fromTheMap() {
        val f = System.getenv("BROUTER_TILES")?.let { File(it, "E15_N65.pmtiles") }
        assumeTrue(f != null && f.isFile)
        PmTiles(f!!).use { pm ->
            // A numbered address in central Tromso (the map keeps them as points).
            val (fx, fy) = TileMath.xy(15, 69.6496, 18.9560)
            val tx = fx.toInt(); val ty = fy.toInt()
            val layer = VectorTile.decode(pm.tile(15, tx, ty)!!).layers["buildings"]!!
            val house = layer.features.first { it.str("addr_housenumber") != null && it.rings.isNotEmpty() }
            val ring = house.rings.first()
            val cx = ring.filterIndexed { i, _ -> i % 2 == 0 }.average() / layer.extent + tx
            val cy = ring.filterIndexed { i, _ -> i % 2 == 1 }.average() / layer.extent + ty
            val name = OfflineAddress.at(pm, TileMath.lat(15, cy), TileMath.lng(15, cx))
            assertNotNull(name)
            assertTrue(name!!, name.endsWith(" " + house.str("addr_housenumber")))
            // Open water far from any street: the nearest place name or nothing,
            // never a street.
            val sea = OfflineAddress.at(pm, 69.75, 17.0)
            assertTrue("$sea", sea == null || !sea.any(Char::isDigit))
        }
    }

    private fun assertNotNullAnd(b: ByteArray?): ByteArray { assertNotNull(b); return b!! }
}
