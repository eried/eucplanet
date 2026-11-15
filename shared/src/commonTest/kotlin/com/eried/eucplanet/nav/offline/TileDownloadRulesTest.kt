package com.eried.eucplanet.nav.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a download reads the server's answers. */
class TileDownloadRulesTest {

    @Test fun aMissingSquare_isNotRetried() {
        // Open sea has no file: retrying it for two hours helps nobody.
        assertTrue(TileDownload.isPermanent(404))
        assertTrue(TileDownload.isPermanent(410))
        assertFalse(TileDownload.isPermanent(503))
        assertFalse(TileDownload.isPermanent(500))
    }

    @Test fun contentRange_givesTheWholeLength() {
        assertEquals(64806390L, TileDownload.totalFromContentRange("bytes */64806390"))
        assertEquals(100L, TileDownload.totalFromContentRange("bytes 0-99/100"))
        assertNull(TileDownload.totalFromContentRange("bytes 0-99/*"))
        assertNull(TileDownload.totalFromContentRange(null))
    }

    @Test fun aPart_resumesOnlyFromTheSameLink() {
        // GitHub ignores If-Range: a part of last month's build must not be
        // finished with this month's. Each mirror build has its own link.
        val url = "https://github.com/eried/eucplanet-maps/releases/download/routing-20261029-1/E10_N55.rd5"
        val meta = TileDownload.partMeta(url, "Thu, 29 Oct 2026 07:00:50 GMT")
        assertEquals("Thu, 29 Oct 2026 07:00:50 GMT", TileDownload.resumeDate(meta, url))
        assertNull(TileDownload.resumeDate(meta, url.replace("20261029", "20261202")))
        assertNull(TileDownload.resumeDate("Thu, 29 Oct 2026 07:00:50 GMT", url), "a part from an older build of the app")
        assertNull(TileDownload.resumeDate(null, url))
    }

    @Test fun huggingFace_resumesByItsETag_forLackOfADate() {
        // Hugging Face's file servers send an ETag and no Last-Modified.
        assertEquals("Thu, 29 Oct 2026 07:00:50 GMT", TileDownload.validator("Thu, 29 Oct 2026 07:00:50 GMT", "\"abc\""))
        assertEquals("\"abc\"", TileDownload.validator(null, "\"abc\""))
        assertEquals("\"abc\"", TileDownload.validator(" ", "\"abc\""))
        assertNull(TileDownload.validator(null, "W/\"abc\""), "a weak ETag cannot resume")
        assertNull(TileDownload.validator(null, null))
    }
}
