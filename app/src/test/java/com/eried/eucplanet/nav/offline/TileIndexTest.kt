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

    private companion object { const val DAY = 86_400_000L }
}
