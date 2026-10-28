package com.eried.eucplanet.platform

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pieces DropboxRepository's link flow and listings stand on, on both phones. */
class DropboxPlatformTest {

    @Test
    fun pkceChallengeMatchesRfc7636() {
        // RFC 7636 appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(sha256(verifier.encodeToByteArray()))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun sha256OfNothing() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }

    @Test
    fun queryEncodesLikeUriEncode() {
        val url = UrlQuery("https://www.dropbox.com/oauth2/authorize")
            .appendQueryParameter("scope", "account_info.read files.content.write")
            .appendQueryParameter("redirect_uri", "db-5auhxf7gswy7j54://1/connect")
            .appendQueryParameter("x", "a~b_c-d.e!f*g'h(i)j æ")
            .build()
        assertEquals(
            "https://www.dropbox.com/oauth2/authorize?scope=account_info.read%20files.content.write" +
                "&redirect_uri=db-5auhxf7gswy7j54%3A%2F%2F1%2Fconnect&x=a~b_c-d.e!f*g'h(i)j%20%C3%A6",
            url,
        )
    }

    @Test
    fun secureRandomStaysInBounds() {
        repeat(500) { assertTrue(secureRandomInt(66) in 0 until 66) }
    }

    @Test
    fun dropboxTimestamps() {
        assertEquals(1_431_445_838L, isoEpochSeconds("2015-05-12T15:50:38Z"))
        assertEquals(1_431_438_638L, isoEpochSeconds("2015-05-12T15:50:38+02:00"))
        assertFailsWith<IllegalArgumentException> { isoEpochSeconds("") }
    }
}
