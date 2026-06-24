package com.eried.eucplanet.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DropboxClientTest {
    private val c = DropboxClient()

    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        // RFC 7636 Appendix B reference vector.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", c.challengeFor(verifier))
    }

    @Test
    fun verifierIsUrlSafeAndLongEnough() {
        val v = c.newVerifier()
        assertTrue(v.length in 43..128, "len=${v.length}")
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" }, "non-unreserved char in $v")
    }

    @Test
    fun authorizeUrlCarriesKeyChallengeAndRedirect() {
        val url = c.authorizeUrl("CHAL123")
        assertTrue(url.contains("client_id=5auhxf7gswy7j54"))
        assertTrue(url.contains("code_challenge=CHAL123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=db-5auhxf7gswy7j54://1/connect"))
        assertTrue(url.contains("token_access_type=offline"))
    }
}
