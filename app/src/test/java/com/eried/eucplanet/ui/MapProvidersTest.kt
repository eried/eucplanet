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
        // Riders have "voyager" / "dark_all" persisted from the carto era.
        assertEquals(MapLayers.LIGHT, MapLayers.byId("voyager").id)
        assertEquals(MapLayers.DARK, MapLayers.byId("dark_all").id)
    }

    @Test fun `the canvas renderers composite the labels layer`() {
        // Studio map element and the HUD tile cache draw single bitmaps, so
        // they fetch the Esri reference (labels) tile and draw it over the
        // base instead of showing a label-less map.
        val studio = File("src/main/java/com/eried/eucplanet/ui/studio/StudioOverlayElements.kt").readText()
        val hud = File("../hud/src/main/java/com/eried/eucplanet/hud/net/HudTileCache.kt").readText()
        assertTrue(studio.contains("_Gray_Reference/"))
        assertTrue(hud.contains("_Gray_Reference/"))
        // Every dark_* legacy slug means dark - dark_nolabels must not fall
        // through to the light basemap.
        assertTrue(hud.contains("style.startsWith(\"dark\")"))
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
