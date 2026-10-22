package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteLinks
import com.eried.eucplanet.nav.SharedRoute
import com.eried.eucplanet.share.ShareLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one scanner tells a route from a group ride, and refuses anything else. */
class ScannedCodeTest {

    @Test fun `a route code opens a route`() {
        val link = RouteLinks.format(SharedRoute("Loop", TravelMode.CYCLING,
            listOf(Waypoint(69.65, 18.95, name = "A"), Waypoint(69.68, 18.98, name = "B"))))!!
        val code = parseScannedCode(link)
        assertTrue(code is ScannedCode.Route)
        assertEquals(2, (code as ScannedCode.Route).route.stops.size)
    }

    @Test fun `a group ride code joins, full link or bare fragment`() {
        val link = ShareLinks.format(ShareLinks.newLink())
        assertTrue(parseScannedCode(link) is ScannedCode.Join)
        assertTrue(parseScannedCode(link.substringAfter('#')) is ScannedCode.Join)
    }

    @Test fun `anything else keeps the scanner looking`() {
        assertNull(parseScannedCode("https://example.com"))
        assertNull(parseScannedCode("hello"))
        assertNull(parseScannedCode("https://eucplanet.ried.no/route#1.broken"))
    }
}
