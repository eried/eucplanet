package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLinksTest {

    private fun stops(n: Int) = (0 until n).map {
        Waypoint(lat = 69.6489 + it * 0.0123, lng = 18.9551 - it * 0.0071, name = if (it % 2 == 0) "Stop ${it + 1}" else "")
    }

    @Test fun `a route survives the round trip`() {
        val route = SharedRoute("Tromsø loop", TravelMode.WALKING,
            listOf(Waypoint(69.64893, 18.95512, name = "Start"), Waypoint(69.6812, 18.9876, radiusM = 50.0),
                Waypoint(69.66, 18.93)))
        val link = RouteLinks.format(route)!!
        assertTrue(link, link.startsWith("https://eucplanet.ried.no/route#1."))
        val back = RouteLinks.parse(link)!!
        assertEquals("Tromsø loop", back.name)
        assertEquals(TravelMode.WALKING, back.mode)
        assertEquals(3, back.stops.size)
        assertEquals(69.64893, back.stops[0].lat, 1e-5)
        assertEquals(18.95512, back.stops[0].lng, 1e-5)
        assertEquals("Start", back.stops[0].name)
        assertEquals(50.0, back.stops[1].radiusM!!, 0.0)
        assertEquals("", back.stops[2].name)
        assertNull(back.stops[2].radiusM)
    }

    @Test fun `a single stop is a place to ride to`() {
        val link = RouteLinks.format(SharedRoute("Cafe", TravelMode.CYCLING, stops(1)))!!
        val back = RouteLinks.parse(link)!!
        assertEquals(1, back.stops.size)
        assertEquals("Stop 1", back.stops[0].name)
    }

    @Test fun `the most stops a route can have fit in a scannable code`() {
        val link = RouteLinks.format(SharedRoute("A long name for a long day out on the wheel", TravelMode.CYCLING, stops(25)))
        assertNotNull(link)
        assertTrue("${link!!.length} chars", link.length <= RouteLinks.MAX_LINK)
        assertEquals(25, RouteLinks.parse(link)!!.stops.size)
    }

    @Test fun `a long track is simplified until it fits, keeping its ends`() {
        // A 40 km wiggle sampled every few metres: thousands of points.
        val track = (0 until 8000).map { i ->
            GeoPoint(69.6 + i * 0.00005, 18.9 + Math.sin(i / 40.0) * 0.002)
        }
        val route = SharedRoute(null, TravelMode.STRAIGHT, listOf(), track)
        val link = RouteLinks.format(route)!!
        assertTrue(link.length <= RouteLinks.MAX_LINK)
        val back = RouteLinks.parse(link)!!
        assertTrue(back.track.size in 2 until track.size)
        assertEquals(track.first().lat, back.track.first().lat, 1e-5)
        assertEquals(track.last().lng, back.track.last().lng, 1e-5)
    }

    @Test fun `the web page's open-in-app path form parses too`() {
        val link = RouteLinks.format(SharedRoute(null, TravelMode.DRIVING, stops(3)))!!
        val pathForm = RouteLinks.BASE + "/" + link.substringAfter('#')
        assertEquals(3, RouteLinks.parse(pathForm)!!.stops.size)
    }

    @Test fun `other links and damaged data are refused`() {
        assertNull(RouteLinks.parse("https://eucplanet.ried.no/share#abc.def"))
        assertNull(RouteLinks.parse("https://eucplanet.ried.no/route#9.AAAA"))
        assertNull(RouteLinks.parse("https://eucplanet.ried.no/route#1.not-base64-!!"))
        assertNull(RouteLinks.parse("https://example.com/route#1.AAAA"))
        assertNull(RouteLinks.format(SharedRoute(null, TravelMode.CYCLING, stops(0))))
    }

    @Test fun `polyline matches the reference encoding`() {
        // Google's documented example.
        val pts = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", RouteLinks.encodePolyline(pts))
        val back = RouteLinks.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(43.252, back[2].lat, 1e-5)
        assertEquals(-126.453, back[2].lng, 1e-5)
    }
}
