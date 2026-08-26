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

    @Test fun `both leaflet surfaces draw the reference layer`() {
        val tripHtml = File("src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt").readText()
        val navHtml = File("src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt").readText()
        assertTrue(tripHtml.contains("if (layer.ref)"))
        assertTrue(navHtml.contains("if (layer.ref)"))
        // The JSON bridge carries the field at all.
        assertTrue(navHtml.contains("ref:'\${l.refUrlTemplate}'"))
    }
}
