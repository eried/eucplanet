package com.eried.eucplanet.share

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ShareLinkTest {
    @Test fun formatAndParse() {
        val link = ShareLink("AAAAAAAAAAAAAAAAAAAAAA", ByteArray(16) { (it + 1).toByte() })
        val url = ShareLinks.format(link)
        assertTrue(url.startsWith("https://eucplanet.ried.no/share#"))
        assertFalse(url.contains("?"))                       // secret only in the fragment
        val back = ShareLinks.parse(url)!!
        assertEquals(link.roomId, back.roomId); assertContentEquals(link.key, back.key)
    }
    @Test fun parseRejectsGarbage() {
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share"))
        assertNull(ShareLinks.parse("https://eucplanet.ried.no/share#short.x"))
        assertNull(ShareLinks.parse("https://other.example/share#AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun parse_acceptsThePathFormTheWebButtonSends() {
        val link = ShareLinks.newLink()
        val frag = ShareLinks.format(link).substringAfter('#')
        val fromPath = ShareLinks.parse(ShareLinks.BASE + "/" + frag)
        assertNotNull(fromPath)
        assertEquals(link.roomId, fromPath!!.roomId)
        assertContentEquals(link.key, fromPath.key)
        assertNull(ShareLinks.parse(ShareLinks.BASE + "/not-a-link"))
    }

    /** The key's last character carries four spare bits; like java.util.Base64,
     *  they are ignored, so a hand-typed or test key still opens the room. */
    @Test
    fun parse_ignoresTheSpareBitsOfTheKey() {
        val link = ShareLinks.parse(ShareLinks.BASE + "#AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB")
        assertNotNull(link)
        assertEquals(16, link!!.key.size)
        assertEquals(16, ShareBase64.decode("_-_-_-_-_-_-_-_-_-_-_-").size)
        assertEquals("AAECAwQFBgcICQoLDA0ODw", ShareBase64.encode(ByteArray(16) { it.toByte() }))
    }
}
