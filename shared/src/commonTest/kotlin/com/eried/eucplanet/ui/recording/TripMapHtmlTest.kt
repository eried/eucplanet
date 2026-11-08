package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.map.MapLayers
import com.eried.eucplanet.map.MapLayersJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The trip viewer's Leaflet page is the same document on both phones. */
class TripMapHtmlTest {

    private fun point(lat: Double, lon: Double) =
        TripDataPoint("2026-11-08 10:00:00.000", 0f, 0f, 0f, 0, 0f, lat, lon, 0f)

    private fun page(
        isLive: Boolean = false,
        offline: String = TripMapHtml.NO_OFFLINE_MAP_JS,
        head: String = TripMapHtml.LEAFLET_CDN_HEAD,
    ) = TripMapHtml.build(
        coordsJson = TripMapHtml.coordsJson(listOf(point(59.9, 10.7), point(59.91, 10.71))),
        fadedCoordsJson = "",
        switchesJson = "[]",
        fadedSwitchesJson = "[]",
        startIncluded = true,
        endIncluded = false,
        startLabelJs = TripMapHtml.quote("10:00 - Ride start"),
        endLabelJs = TripMapHtml.quote(""),
        isLive = isLive,
        initialType = "DARK",
        offlineMapJs = offline,
        leafletHead = head,
    )

    @Test
    fun coordinatesAreLatLonPairs() {
        assertEquals("[59.9,10.7],[59.91,10.71]", TripMapHtml.coordsJson(listOf(point(59.9, 10.7), point(59.91, 10.71))))
        assertEquals("", TripMapHtml.coordsJson(emptyList()))
    }

    @Test
    fun thePageCarriesTheTraceAndItsState() {
        val html = page()
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.endsWith("</script></body></html>"))
        assertTrue(html.contains("var coords=[[59.9,10.7],[59.91,10.71]];"))
        assertTrue(html.contains("var fadedCoords=[];"))
        assertTrue(html.contains("var startIncluded=true;"))
        assertTrue(html.contains("var endIncluded=false;"))
        assertTrue(html.contains("var startLabel=\"10:00 - Ride start\";"))
        assertTrue(html.contains("window.setMapType('DARK');"))
    }

    @Test
    fun everyHookTheScreenCallsIsDefined() {
        val html = page()
        for (hook in listOf("setTrace", "setMapType", "updateLivePoint", "updateScrubPoint",
            "clearScrubPoint", "updateComparePoint", "clearComparePoint")) {
            assertTrue(html.contains("window.$hook"), hook)
        }
    }

    @Test
    fun theLayerTableIsTheRegistry() {
        val html = page()
        assertTrue(html.contains("var MAP_LAYERS = ${MapLayersJs.table()};"))
        for (layer in MapLayers.ALL) assertTrue(MapLayersJs.table().contains("'${layer.id}':{url:'${layer.urlTemplate}'"), layer.id)
    }

    @Test
    fun theHeadAndTheOfflineMapAreThePhones() {
        val cdn = page()
        assertTrue(cdn.contains("https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"))
        assertTrue(cdn.contains(TripMapHtml.NO_OFFLINE_MAP_JS))
        val inlined = page(head = "<style>/*css*/</style><script>/*leaflet*/</script>", offline = "/*offline*/")
        assertFalse(inlined.contains("unpkg.com"))
        assertTrue(inlined.contains("<script>/*leaflet*/</script>"))
        assertTrue(inlined.contains("/*offline*/\n  var offlineMap=eucOfflineMap(map);"))
    }

    @Test
    fun liveModeIsMarked() {
        assertTrue(page(isLive = true).contains("live mode: waiting for updateLivePoint()"))
        assertFalse(page(isLive = false).contains("live mode"))
    }

    @Test
    fun wheelNamesCannotBreakOutOfTheScript() {
        val sw = TripMapHtml.switchesJson(listOf(WheelSwitchMarker(3, 1.5, 2.5, "x</script><b>", true)))
        assertFalse(sw.contains("</script>"))
        // Key order is the phone's org.json (insertion order); the JVM test
        // library's is a hash, so only the members are checked.
        for (member in listOf("\"idx\":3", "\"lat\":1.5", "\"lon\":2.5", "\"change\":true")) {
            assertTrue(sw.contains(member), member)
        }
        val faded = TripMapHtml.fadedSwitchesJson(listOf(WheelSwitchMarker(0, 1.5, 2.5, "ignored", false)))
        assertFalse(faded.contains("idx"))
        assertFalse(faded.contains("ignored"))
        assertTrue(faded.contains("\"change\":false"))
        assertFalse(TripMapHtml.quote("a</script>").contains("</script>"))
    }
}
