package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared GPX reader reads what Android's GpxIO (XmlPullParser, namespaces
 * off) reads. Each case is a rule of GpxIO.parse.
 */
class GpxTextTest {

    private fun gpx(body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"x\" xmlns=\"http://www.topografix.com/GPX/1/1\">$body</gpx>"

    @Test
    fun ourOwnSave_readsBackItsStopsNameRadiusAndLine() {
        val route = NavRoute(
            name = "Oslo & fjord <loop>",
            waypoints = listOf(
                Waypoint(59.9139, 10.7522, "Start \"here\""),
                Waypoint(59.9075, 10.7602, "", 25.0),
            ),
            travelMode = TravelMode.CYCLING,
            geometry = listOf(GeoPoint(59.9139, 10.7522), GeoPoint(59.91, 10.755), GeoPoint(59.9075, 10.7602)),
            maneuvers = emptyList(),
            totalDistanceM = 1000.0,
        )
        val back = GpxText.parse(GpxText.write(route))
        assertEquals("Oslo & fjord <loop>", back.name)
        assertEquals(
            listOf(Waypoint(59.9139, 10.7522, "Start \"here\""), Waypoint(59.9075, 10.7602, "", 25.0)),
            back.routePoints,
        )
        assertEquals(3, back.track.size)
        assertEquals(GeoPoint(59.91, 10.755), back.track[1])
        assertTrue(back.contents() is GpxData.Contents.Stops)
    }

    @Test
    fun aLockedTrack_writesItsLineAndPlaces_andReopensAsATrack() {
        val line = listOf(GeoPoint(59.0, 10.0), GeoPoint(59.1, 10.1), GeoPoint(59.2, 10.2))
        val route = NavRoute(
            name = "Track",
            waypoints = listOf(Waypoint(59.0, 10.0, "a"), Waypoint(59.1, 10.1, "Cafe"), Waypoint(59.2, 10.2, "b")),
            travelMode = TravelMode.CYCLING,
            geometry = line,
            maneuvers = emptyList(),
            totalDistanceM = 1.0,
            track = line,
        )
        val text = GpxText.write(route)
        assertTrue("<rte>" !in text)
        val back = GpxText.parse(text)
        val c = back.contents() as GpxData.Contents.Track
        assertEquals(line, c.track)
        assertEquals(listOf(Waypoint(59.1, 10.1, "Cafe")), c.places)
    }

    @Test
    fun coordinatesAreWrittenWithSixDecimals_whateverTheLocale() {
        val route = NavRoute("n", listOf(Waypoint(1.5, -2.25, "")), TravelMode.CYCLING, emptyList(), emptyList(), 0.0)
        assertTrue("<rtept lat=\"1.500000\" lon=\"-2.250000\"></rtept>" in GpxText.write(route))
    }

    @Test
    fun theFirstNameOfMetadataRteOrTrk_namesTheRoute_andRouteIsTheFallback() {
        assertEquals("Meta", GpxText.parse(gpx("<metadata><name>Meta</name></metadata><trk><name>Trk</name></trk>")).name)
        assertEquals("Trk", GpxText.parse(gpx("<trk><name> Trk </name></trk>")).name)
        assertEquals("Route", GpxText.parse(gpx("<wpt lat=\"1\" lon=\"2\"/>")).name)
        assertEquals("Route", GpxText.parse(gpx("<metadata><name>  </name></metadata>")).name)
    }

    @Test
    fun otherAppsExports_selfClosingPoints_anyCase_cdataAndEntities() {
        val data = GpxText.parse(
            gpx(
                "<!-- a comment --><WPT lat=\"59.1\" lon='10.2'><name><![CDATA[Fish & chips]]></name></WPT>" +
                    "<trk><trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"3\" lon=\"4\"><ele>5</ele></trkpt></trkseg></trk>" +
                    "<wpt lat=\"bad\" lon=\"7\"><name>&#x00C6;r&#248; &amp; Co</name></wpt>",
            ),
        )
        assertEquals(listOf(Waypoint(59.1, 10.2, "Fish & chips"), Waypoint(0.0, 7.0, "Ærø & Co")), data.places)
        assertEquals(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)), data.track)
        assertTrue(data.contents() is GpxData.Contents.Track)
    }

    @Test
    fun aRadiusCountsOnlyInsideAStop_andANameAfterAStopStaysWithIt() {
        val data = GpxText.parse(
            gpx(
                "<rte><rtept lat=\"1\" lon=\"1\"><extensions><radius>40</radius></extensions></rtept>" +
                    "<rtept lat=\"2\" lon=\"2\"><name/></rtept></rte>" +
                    "<trk><radius>99</radius><trkseg><trkpt lat=\"3\" lon=\"3\"><radius>7</radius></trkpt></trkseg></trk>",
            ),
        )
        assertEquals(listOf(Waypoint(1.0, 1.0, "", 40.0), Waypoint(2.0, 2.0, "", null)), data.routePoints)
    }

    @Test
    fun prefixedElements_areNotGpxElements_asWithNamespacesOff() {
        val data = GpxText.parse("<gpx:gpx xmlns:gpx=\"x\"><gpx:wpt lat=\"1\" lon=\"2\"/></gpx:gpx>")
        assertEquals(emptyList(), data.places)
    }

    @Test
    fun aDoctypeAndProcessingInstructions_areSkipped() {
        val data = GpxText.parse(
            "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x \"y\">]><?pi data?><gpx><wpt lat=\"1\" lon=\"2\"/></gpx>",
        )
        assertEquals(1, data.places.size)
    }

    @Test
    fun aBrokenDocument_throws_asThePullParserDoes() {
        assertFailsWith<IllegalArgumentException> { GpxText.parse("<gpx><wpt lat=\"1\" lon=\"2\"></gpx>") }
        assertFailsWith<IllegalArgumentException> { GpxText.parse("<gpx><wpt lat=\"1\" lon=\"2\">") }
        assertFailsWith<IllegalArgumentException> { GpxText.parse("<gpx><wpt><name>a<b/></name></wpt></gpx>") }
        assertFailsWith<IllegalArgumentException> { GpxText.parse("<gpx><wpt><name>&nbsp;</name></wpt></gpx>") }
    }

    @Test
    fun encodings_byMarkOrDeclaration() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<gpx><wpt lat=\"1\" lon=\"2\"><name>ø</name></wpt></gpx>".encodeToByteArray()
        assertEquals("ø", GpxText.parse(utf8Bom).places.single().name)

        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><gpx><wpt lat=\"1\" lon=\"2\"><name>".encodeToByteArray() +
            byteArrayOf(0xF8.toByte()) + "</name></wpt></gpx>".encodeToByteArray()
        assertEquals("ø", GpxText.parse(latin).places.single().name)

        val text = "<gpx><wpt lat=\"1\" lon=\"2\"><name>ø</name></wpt></gpx>"
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.flatMap { listOf((it.code and 0xFF).toByte(), (it.code shr 8).toByte()) }.toByteArray()
        assertEquals("ø", GpxText.parse(utf16le).places.single().name)
    }
}
