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

    @Test fun aPart_resumesOnlyFromTheSameLink() {
        // GitHub ignores If-Range: a part of last month's build must not be
        // finished with this month's. Each mirror build has its own link.
        val url = "https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5"
        val meta = TileDownloadWorker.partMeta(url, "Thu, 29 Oct 2026 07:00:50 GMT")
        assertEquals("Thu, 29 Oct 2026 07:00:50 GMT", TileDownloadWorker.resumeDate(meta, url))
        assertNull(TileDownloadWorker.resumeDate(meta, url.replace("20261029", "20261202")))
        assertNull("a part from an older build of the app", TileDownloadWorker.resumeDate("Thu, 29 Oct 2026 07:00:50 GMT", url))
        assertNull(TileDownloadWorker.resumeDate(null, url))
    }
}
