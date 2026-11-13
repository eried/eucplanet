package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.hud.protocol.MapLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One stored value, navMapType, picks the map on the phone, the watch, the
 * car screen and the offline download grid. A blank value has to mean the
 * same layer on all of them, and that layer is the settings default.
 */
class NavMapTypeFallbackTest {

    @Test fun `the default and the registry fallback are the same layer`() {
        assertEquals(MapLayers.OSM, AppSettings().navMapType)
        assertEquals(MapLayers.OSM, MapLayers.byId("").id)
    }

    @Test fun `no screen swaps a blank map type for a layer of its own`() {
        val sources = listOf(
            "src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt",
            "src/main/java/com/eried/eucplanet/ui/settings/offline/OfflineDataViewModel.kt",
        )
        // The fallback, wherever the line is broken: navMapType ... ifBlank { X }
        val fallback = Regex("""navMapType\s*\.ifBlank\s*\{\s*([^}]+?)\s*\}""")
        for (path in sources) {
            val found = fallback.findAll(File(path).readText()).map { it.groupValues[1] }.toList()
            assertTrue("$path no longer has a blank fallback to check", found.isNotEmpty())
            for (value in found) {
                assertTrue(
                    "$path falls back to $value, not OSM",
                    value.endsWith("MapLayers.OSM") || value == "\"OSM\"",
                )
            }
        }
    }
}
