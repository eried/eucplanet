package com.eried.eucplanet.ui

import com.eried.eucplanet.hud.protocol.MapLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Light and Dark come from Esri's Canvas basemaps, not CARTO.
 *
 * CARTO's keyless basemaps.cartocdn.com endpoints are being key-gated
 * (eucviewer's dark style already answers "API key required"), so no map
 * surface may depend on them. Esri splits Canvas into a base and a labels
 * reference layer; the Leaflet surfaces must draw both.
 */
class MapProvidersTest {

    @Test fun `no surface depends on carto`() {
        val sources = listOf(
            "../hud-protocol/src/main/java/com/eried/eucplanet/hud/protocol/MapLayers.kt",
            "../hud/src/main/java/com/eried/eucplanet/hud/net/HudTileCache.kt",
            "src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt",
            "src/main/java/com/eried/eucplanet/map/MapTileCache.kt",
            "src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt",
        )
        val offenders = sources.filter { File(it).readText().contains("cartocdn") }
        assertTrue("still on carto: $offenders", offenders.isEmpty())
    }

    @Test fun `light and dark are esri canvas, base plus reference`() {
        val light = MapLayers.byId(MapLayers.LIGHT)
        val dark = MapLayers.byId(MapLayers.DARK)
        assertTrue(light.urlTemplate.contains("Canvas/World_Light_Gray_Base"))
        assertTrue(light.refUrlTemplate.contains("Canvas/World_Light_Gray_Reference"))
        assertTrue(dark.urlTemplate.contains("Canvas/World_Dark_Gray_Base"))
        assertTrue(dark.refUrlTemplate.contains("Canvas/World_Dark_Gray_Reference"))
        // The licence line names the new provider and, per ODbL, the OSM
        // contributors the canvas data derives from.
        assertTrue(light.attribution.contains("Esri"))
        assertTrue(light.attribution.contains("OpenStreetMap"))
        // Canvas renders to level 16; deeper zooms upscale rather than 404.
        assertEquals(16, light.maxNativeZoom)
        assertEquals(16, dark.maxNativeZoom)
    }

    @Test fun `the legacy carto slugs still resolve`() {
        // Riders have carto slugs persisted from that era; every light_* and
        // voyager_* one was a light chart, every dark_* one a dark chart.
        for (slug in listOf("voyager", "voyager_nolabels", "voyager_labels_under",
            "voyager_only_labels", "positron", "light_all", "light_nolabels", "light_only_labels")) {
            assertEquals(slug, MapLayers.LIGHT, MapLayers.byId(slug).id)
        }
        for (slug in listOf("dark_all", "dark_nolabels", "dark_only_labels", "dark_matter", "dark_matter_nolabels")) {
            assertEquals(slug, MapLayers.DARK, MapLayers.byId(slug).id)
        }
    }

    @Test fun `every code the hud style picker offers resolves to its own layer`() {
        // The HUD's cache used to map its codes itself and turned all seven
        // into the light chart, so the picker on the phone changed nothing on
        // the HUD and dark was impossible. The registry resolves them now,
        // case-insensitively, so "osm" and "OSM" are one layer.
        val expected = mapOf(
            "osm" to MapLayers.OSM, "cyclosm" to MapLayers.CYCLOSM, "topo" to MapLayers.TOPO,
            "hot" to MapLayers.HUMANITARIAN, "satellite" to MapLayers.SATELLITE,
            "light" to MapLayers.LIGHT, "dark" to MapLayers.DARK,
        )
        expected.forEach { (code, id) -> assertEquals(code, id, MapLayers.byId(code).id) }
        assertEquals(MapLayers.OSM, MapLayers.byId("no such layer").id)
        assertEquals(null, MapLayers.refTileUrl("osm", 16, 1, 2))
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/16/2/1",
            MapLayers.refTileUrl("dark", 16, 1, 2),
        )
    }

    @Test fun `no canvas renderer asks the provider for tiles deeper than it renders`() {
        // The HUD's default zoom is 17 and Esri Canvas stops at 16: the whole
        // HUD map screen was a grid of "Map data not yet available" tiles in
        // 0.21.0. Each renderer caps its tile zoom at the layer's native depth
        // and draws those tiles scaled up.
        val renderers = listOf(
            "../hud/src/main/java/com/eried/eucplanet/hud/ui/screens/MapScreen.kt",
            "../hud/src/main/java/com/eried/eucplanet/hud/overlay/OverlayElements.kt",
            "src/main/java/com/eried/eucplanet/ui/studio/StudioOverlayElements.kt",
        )
        val offenders = renderers.filter { !File(it).readText().contains("maxNativeZoom") }
        assertTrue("fetch past the provider's depth: $offenders", offenders.isEmpty())
    }

    @Test fun `the canvas renderers composite the labels layer`() {
        // Studio map element and the HUD tile cache draw single bitmaps, so
        // they fetch the Esri reference (labels) tile and draw it over the
        // base instead of showing a label-less map.
        // The Studio map element draws through the shared MapTileCache since
        // PR #25, so the compositing lives there now.
        val studio = File("src/main/java/com/eried/eucplanet/map/MapTileCache.kt").readText()
        val hud = File("../hud/src/main/java/com/eried/eucplanet/hud/net/HudTileCache.kt").readText()
        assertTrue(studio.contains("_Gray_Reference/"))
        // The HUD cache takes both URLs from the registry rather than a table
        // of its own: that table is how the picker codes got lost once.
        assertTrue(hud.contains("MapLayers.refTileUrl("))
        assertTrue(hud.contains("MapLayers.tileUrl("))
        assertTrue(hud.contains("MapLayers.byId("))
    }

    @Test fun `the hud style picker offers real styles, not carto slugs`() {
        val settings = File("src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt").readText()
        // Anchor on the picker's own comment: "val options" appears many
        // times in the settings screen.
        val options = settings.substringAfter("Raw internal codes on purpose")
            .substringAfter("listOf(").substringBefore(")")
        assertTrue(options.contains("\"light\""))
        assertTrue(options.contains("\"dark\""))
        assertTrue("the ten carto slugs are back", !settings.contains("voyager_labels_under"))
    }

    @Test fun `both leaflet surfaces draw the reference layer`() {
        val tripHtml = File("src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt").readText()
        val navHtml = File("src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt").readText()
        assertTrue(tripHtml.contains("if (layer.ref)"))
        assertTrue(navHtml.contains("if (layer.ref)"))
        // The JSON bridge carries the field at all.
        assertTrue(navHtml.contains("ref:'\${l.refUrlTemplate}'"))
    }
}
