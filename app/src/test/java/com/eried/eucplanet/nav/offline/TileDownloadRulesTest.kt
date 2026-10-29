package com.eried.eucplanet.nav.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a download reads the server's answers. */
class TileDownloadRulesTest {

    @Test fun aMissingSquare_isNotRetried() {
        // Open sea has no file: retrying it for two hours helps nobody.
        assertTrue(TileDownloadWorker.isPermanent(404))
        assertTrue(TileDownloadWorker.isPermanent(410))
        assertFalse(TileDownloadWorker.isPermanent(503))
        assertFalse(TileDownloadWorker.isPermanent(500))
    }

    @Test fun contentRange_givesTheWholeLength() {
        assertEquals(64806390L, TileDownloadWorker.totalFromContentRange("bytes */64806390"))
        assertEquals(100L, TileDownloadWorker.totalFromContentRange("bytes 0-99/100"))
        assertNull(TileDownloadWorker.totalFromContentRange("bytes 0-99/*"))
        assertNull(TileDownloadWorker.totalFromContentRange(null))
    }
}
