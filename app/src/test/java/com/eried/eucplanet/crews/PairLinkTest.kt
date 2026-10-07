package com.eried.eucplanet.crews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parsing and trust rules behind crews pairing.
 *
 * Worth pinning here rather than only by pointing a phone at a code: [PairLink.trust] is the
 * single thing standing between a scanned QR and this rider's store_id leaving for whatever
 * server the code names, and the parser is reachable by anything that can put a QR in front
 * of a camera.
 *
 * A plain JVM test: the parser is deliberately pure Kotlin with no android.net.Uri, both so it
 * can be pinned here and because a URL parser with surprising corners is the wrong thing to
 * put in front of a security decision.
 */
class PairLinkTest {

    private val prod = PairLink.productionOrigin()

    @Test
    fun `the url form the qr encodes is parsed`() {
        val link = PairLink.parse("$prod/p/AB12CD")
        assertEquals("AB12CD", link?.code)
        assertEquals(prod, link?.host)
    }

    @Test
    fun `the app scheme form carries its own host`() {
        val link = PairLink.parse("eucplanet://pair?code=xy34zw&host=http://10.0.2.2:8000")
        assertEquals("XY34ZW", link?.code)      // normalised, because codes are shown upper case
        assertEquals("http://10.0.2.2:8000", link?.host)
    }

    @Test
    fun `a port survives the round trip`() {
        // the whole point of carrying the host: a laptop is never on port 443
        assertEquals("http://192.168.1.40:8000",
            PairLink.parse("http://192.168.1.40:8000/p/ABCDEF")?.host)
    }

    @Test
    fun `codes that are not codes are refused`() {
        assertNull(PairLink.parse("$prod/p/"))
        assertNull(PairLink.parse("$prod/p/ab"))                    // too short
        assertNull(PairLink.parse("$prod/p/abcdefghijklmnop"))      // too long
        assertNull(PairLink.parse("$prod/p/ab*cd!"))                // not alphanumeric
    }

    @Test
    fun `other qr codes are ignored so the scanner keeps looking`() {
        assertNull(PairLink.parse(null))
        assertNull(PairLink.parse(""))
        assertNull(PairLink.parse("WIFI:S=cafe;T=WPA;P=hunter2;;"))
        assertNull(PairLink.parse("https://example.com"))
        assertNull(PairLink.parse("$prod/riders/abc"))              // our host, another page
        assertNull(PairLink.parse("$prod/p/ABC123/extra"))          // longer path, not ours
        assertNull(PairLink.parse("eucplanet://share?code=ABC123")) // our scheme, another job
    }

    @Test
    fun `the usual server pairs without comment`() {
        val link = PairLink.parse("$prod/p/ABC123")!!
        assertEquals(PairTrust.PRODUCTION, link.trust(developerMode = false))
        assertEquals(PairTrust.PRODUCTION, link.trust(developerMode = true))
    }

    @Test
    fun `anywhere else is refused unless the rider turned developer mode on`() {
        val link = PairLink.parse("https://not-eucstats.example.com/p/ABC123")!!
        assertEquals(PairTrust.REFUSED, link.trust(developerMode = false))
        assertEquals(PairTrust.DEVELOPER, link.trust(developerMode = true))
    }

    @Test
    fun `a private address is not trusted for being private`() {
        // "it is a LAN address" is a convenience, not a safety property: the person holding
        // the printed QR code may well be on the same wifi as the person scanning it
        val link = PairLink.parse("http://192.168.1.40:8000/p/ABC123")!!
        assertEquals(PairTrust.REFUSED, link.trust(developerMode = false))
    }

    @Test
    fun `userinfo cannot disguise the real host`() {
        // https://eucstats.ried.no@attacker.example/p/CODE reads as the real host to a person
        // and as the attacker's to a parser, so the whole shape is refused
        val host = prod.removePrefix("https://").removePrefix("http://")
        assertNull(PairLink.parse("https://$host@attacker.example/p/ABC123"))
    }

    @Test
    fun `a lookalike host does not pass as the real one`() {
        val host = prod.removePrefix("https://").removePrefix("http://")
        val evil = PairLink.parse("https://$host.attacker.example/p/ABC123")!!
        assertEquals(PairTrust.REFUSED, evil.trust(developerMode = false))
    }
}
