package com.eried.eucplanet.nav.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Only finished tiles count; a half download must never reach BRouter. */
class OfflineTileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = OfflineTileStore(tmp.newFolder("routing"))

    @Test fun aFinishedTile_counts() {
        val s = store()
        s.finalFile("E10_N55").writeBytes(ByteArray(10))
        assertTrue(s.has("E10_N55"))
        assertEquals(mapOf("E10_N55" to 10L), s.downloaded())
        assertEquals(10L, s.usedBytes())
    }

    @Test fun aPartialTile_doesNotCount() {
        val s = store()
        s.partFile("E10_N55").writeBytes(ByteArray(10))
        assertFalse(s.has("E10_N55"))
        assertTrue(s.downloaded().isEmpty())
    }

    @Test fun anEmptyTile_doesNotCount() {
        val s = store()
        s.finalFile("E10_N55").createNewFile()
        assertFalse(s.has("E10_N55"))
    }

    @Test fun covers_needsEveryTile() {
        val s = store()
        s.finalFile("E10_N55").writeBytes(ByteArray(1))
        assertTrue(s.covers(setOf("E10_N55")))
        assertFalse(s.covers(setOf("E10_N55", "E15_N55")))
        assertFalse(s.covers(emptySet<String>()))
    }

    @Test fun delete_removesTileAndPart() {
        val s = store()
        s.finalFile("E10_N55").writeBytes(ByteArray(1))
        s.partFile("E10_N55").writeBytes(ByteArray(1))
        s.delete("E10_N55")
        assertFalse(s.finalFile("E10_N55").exists())
        assertFalse(s.partFile("E10_N55").exists())
    }

    @Test fun otherFiles_areNotTiles() {
        val s = store()
        s.lookupsFile().writeBytes(ByteArray(5))
        assertTrue(s.downloaded().isEmpty())
    }
}
