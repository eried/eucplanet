package com.eried.eucplanet.hud.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preset over the map: the map underneath answers UP and DOWN as it does
 * on the map screens, and the guide says so.
 */
class HudCustomMapTest {

    private fun onCustomMap(): HudUiController {
        val c = HudUiController()
        c.applyEnabledScreens(listOf("CustomMap"))
        assertEquals(HudUiController.Screen.CustomMap, c.current)
        return c
    }

    @Test fun `up and down zoom the map under the preset`() {
        val c = onCustomMap()
        val start = c.mapZoom
        c.upAction()
        assertEquals(start + 1f, c.mapZoom)
        c.downAction()
        c.downAction()
        assertEquals(start - 1f, c.mapZoom)
    }

    @Test fun `the guide names the zoom`() {
        val a = onCustomMap().shortActions()
        assertEquals("Zoom in", a.up)
        assertEquals("Zoom out", a.down)
    }
}
