package com.eried.eucplanet.nav.offline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The server's file listing gives each tile's size and date. */
class TileIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private val listing = javaClass.classLoader!!.getResource("segments4_listing.html")!!.readText()

    @Test fun listing_givesTilesWithSizes() {
        val tiles = TileIndex.parseListing(listing).associateBy { it.name }
        assertEquals(12235584L, tiles.getValue("E0_N10").bytes)
        assertEquals(333L, tiles.getValue("W5_S5").bytes)
        assertFalse("folders and notes are not tiles", tiles.containsKey("diff"))
        assertTrue(tiles.values.all { RoutingTiles.isTileName(it.name) })
    }

    @Test fun listing_givesTheDate() {
        val t = TileIndex.parseListing(listing).first { it.name == "E0_N10" }
        // 28-Oct-2026 01:03, give or take the server's time zone.
        assertEquals(1_793_145_780_000.0, t.dateMs.toDouble(), 2 * 3_600_000.0)
    }

    @Test fun garbage_givesNothing() {
        assertTrue(TileIndex.parseListing("<html>oops</html>").isEmpty())
        assertTrue(TileIndex.parseListing("").isEmpty())
    }

    @Test fun anOlderLocalTile_needsAnUpdate() {
        val f = tmp.newFile("E0_N10.rd5").apply { writeBytes(ByteArray(1)) }
        val remote = RemoteTile("E0_N10", 1, dateMs = 1_793_145_780_000)
        f.setLastModified(remote.dateMs - 8 * DAY)
        assertTrue(TileIndex.needsUpdate(f, remote))
        f.setLastModified(remote.dateMs)
        assertFalse(TileIndex.needsUpdate(f, remote))
        f.setLastModified(remote.dateMs - 3_600_000)
        assertFalse("a time zone apart is the same build", TileIndex.needsUpdate(f, remote))
    }

    @Test fun aMissingLocalTile_isNotAnUpdate() =
        assertFalse(TileIndex.needsUpdate(File(tmp.root, "none.rd5"), RemoteTile("E0_N10", 1, 0)))

    private val mirror = """
        {"version":1,"lookups":{"name":"lookups","bytes":31604,"date":1779281196000,
          "url":"https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/lookups.dat"},
         "tiles":[
          {"name":"E10_N55","bytes":64858754,"date":1793232180000,
           "url":"https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5"},
          {"name":"../evil","bytes":1,"date":1,"url":"https://x/y.rd5"},
          {"name":"W5_S5","bytes":333,"date":1793232180000,"url":""}
         ]}
    """.trimIndent()

    @Test fun aMirrorIndex_givesTilesWithTheirLinks() {
        val idx = TileIndex.parseMirror(mirror)!!
        val t = idx.tiles.single()
        assertEquals(RemoteTile("E10_N55", 64858754, 1793232180000,
            "https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5"), t)
        assertTrue(idx.lookupsUrl.endsWith("/lookups.dat"))
    }

    @Test fun aSquareWithAMap_countsBothSizes() {
        val json = mirror.replace(
            """"url":"https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5"}""",
            """"url":"https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5",""" +
                """"map":{"url":"https://github.com/eried/eucplanet-maps/releases/download/map-20261029-1/E10_N55.pmtiles","bytes":100,"date":1793232180000}}""",
        )
        val t = TileIndex.parseMirror(json)!!.tiles.single()
        assertEquals(100L, t.map!!.bytes)
        assertEquals(64858754L + 100L, t.totalBytes)
    }

    @Test fun aRoutingOnlySquare_getsItsMapAsAnUpdate() {
        val route = tmp.newFile("E10_N55.rd5").apply { writeBytes(ByteArray(1)) }
        val map = File(tmp.root, "E10_N55.pmtiles")
        val remote = RemoteTile("E10_N55", 1, 0, map = RemoteMap("https://x/E10_N55.pmtiles", 5, 1_793_145_780_000))
        assertTrue(TileIndex.mapNeedsUpdate(route, map, remote))
        map.writeBytes(ByteArray(5))
        map.setLastModified(remote.map!!.dateMs)
        assertFalse(TileIndex.mapNeedsUpdate(route, map, remote))
        assertFalse("no map on the server", TileIndex.mapNeedsUpdate(route, File(tmp.root, "x"), remote.copy(map = null)))
        assertFalse("square not downloaded", TileIndex.mapNeedsUpdate(File(tmp.root, "none.rd5"), map, remote))
    }

    @Test fun aBrokenMirrorIndex_givesNull() {
        assertEquals(null, TileIndex.parseMirror("<html>404</html>"))
        assertEquals(null, TileIndex.parseMirror("""{"tiles":[]}"""))
    }

    @Test fun aJsonAddress_isAMirror_elseTheBrouterFolders() {
        val m = TileSource.of("https://raw.githubusercontent.com/eried/eucplanet-maps/main/routing/index.json")
        assertTrue(m.isMirror)
        val b = TileSource.of("https://brouter.de/brouter/")
        assertFalse(b.isMirror)
        assertEquals("https://brouter.de/brouter/segments4/E10_N55.rd5", b.tileUrl("E10_N55"))
        assertEquals("https://brouter.de/brouter/profiles2/lookups.dat", b.lookupsUrl)
        assertEquals("https://brouter.de/brouter/segments4/", b.listUrl)
    }

    private companion object { const val DAY = 86_400_000L }
}
