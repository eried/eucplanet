package com.eried.eucplanet.share

import org.junit.Assert.*
import org.junit.Test

class ShareLinkTest {
    @Test fun formatAndParse() {
        val link = ShareLink("AAAAAAAAAAAAAAAAAAAAAA", ByteArray(16) { (it + 1).toByte() })
        val url = ShareLinks.format(link)
        assertTrue(url.startsWith("https://eucplanet.ried.no/share#"))
        assertFalse(url.contains("?"))                       // secret only in the fragment
        val back = ShareLinks.parse(url)!!
        assertEquals(link.roomId, back.roomId); assertArrayEquals(link.key, back.key)
    }
    @Test fun parseRejectsGarbage() {
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share"))
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share#short.x"))
        assertNull(ShareLinks.parse("https://other.example/share#AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA"))
    }
}
