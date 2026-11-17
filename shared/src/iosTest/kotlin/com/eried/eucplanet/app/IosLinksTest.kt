package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.nav.RouteLinks
import com.eried.eucplanet.nav.SharedRoute
import com.eried.eucplanet.share.ShareLinks
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The links the app opens with. The site's "Open in EUC Planet" writes
 * eucplanet://share/<room.key> and eucplanet://route/<1.data> (docs/share.html,
 * docs/route.html): a shape changed here and not there is a button that opens
 * the app and nothing else.
 */
class IosLinksTest {

    private val share = ShareLinks.newLink()
    private val shareFragment = ShareLinks.format(share).substringAfter('#')

    private val route = SharedRoute(
        name = "Sunday loop",
        mode = TravelMode.CYCLING,
        stops = listOf(Waypoint(lat = 59.91, lng = 10.75, name = "Start"), Waypoint(lat = 59.95, lng = 10.80, name = "")),
        track = emptyList(),
    )
    private val routeFragment = RouteLinks.format(route)!!.substringAfter('#')

    @Test fun theSitesButtonJoinsTheShare() {
        val opened = assertIs<IosLinks.Opened.Join>(IosLinks.read("eucplanet://share/$shareFragment"))
        assertEquals(share.roomId, opened.link.roomId)
        assertContentEquals(share.key, opened.link.key)
    }

    @Test fun theSitesButtonOpensTheRoute() {
        val opened = assertIs<IosLinks.Opened.Route>(IosLinks.read("eucplanet://route/$routeFragment"))
        assertEquals("Sunday loop", opened.route.name)
        assertEquals(2, opened.route.stops.size)
        assertEquals("Start", opened.route.stops[0].name)
        assertEquals(59.91, opened.route.stops[0].lat, 0.0001)
    }

    @Test fun theLinkAsItIsSharedReadsToo() {
        assertIs<IosLinks.Opened.Join>(IosLinks.read(ShareLinks.format(share)))
        assertIs<IosLinks.Opened.Route>(IosLinks.read(RouteLinks.format(route)!!))
    }

    @Test fun aBrokenShareIsToldAndABrokenRouteIsNot() {
        assertEquals(IosLinks.Opened.BadShare, IosLinks.read("eucplanet://share/nothing"))
        assertEquals(IosLinks.Opened.BadShare, IosLinks.read("eucplanet://share"))
        assertEquals(IosLinks.Opened.BadRoute, IosLinks.read("eucplanet://route/1.broken"))
    }

    @Test fun theOtherLinksAreLeftToTheirOwners() {
        assertNull(IosLinks.read("eucplanet://pair?code=ABCD"))
        assertNull(IosLinks.read("eucplanet://weather"))
        assertNull(IosLinks.read("https://example.com/share#$shareFragment"))
    }
}
