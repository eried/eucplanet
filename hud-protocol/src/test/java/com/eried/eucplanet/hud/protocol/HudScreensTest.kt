package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carousel the phone ships, held to what the settings list shows.
 *
 * The settings list reads a blank enabled set as "every screen" and puts a
 * screen the saved order does not name at the end. The wire has to say the
 * same, or a screen shows as switched on and never reaches the HUD.
 */
class HudScreensTest {

    @Test fun `a rider who has chosen nothing gets every screen`() {
        assertEquals(HudScreens.DEFAULT, HudScreens.carousel("", ""))
        assertTrue("MapNav" in HudScreens.carousel("", ""))
    }

    @Test fun `no screen is listed twice`() {
        assertEquals(HudScreens.DEFAULT.size, HudScreens.DEFAULT.toSet().size)
    }

    @Test fun `the saved order filtered by the enabled set`() {
        assertEquals(
            listOf("Map", "Dashboard"),
            HudScreens.carousel(
                "Map,Camera,Dashboard," + HudScreens.DEFAULT.joinToString(","),
                "Dashboard,Map"
            )
        )
    }

    @Test fun `reordering alone keeps every screen on, in the rider's order`() {
        val reordered = listOf("BigClock") + (HudScreens.DEFAULT - "BigClock")
        assertEquals(reordered, HudScreens.carousel(reordered.joinToString(","), ""))
    }

    @Test fun `toggling alone keeps the default order`() {
        assertEquals(
            listOf("Dashboard", "MapNav", "Nav"),
            HudScreens.carousel("", "Nav,MapNav,Dashboard")
        )
    }

    @Test fun `a screen newer than the saved order goes at the end`() {
        val before = HudScreens.DEFAULT - "MapNav"
        assertEquals(
            before + "MapNav",
            HudScreens.carousel(before.joinToString(","), "")
        )
        assertEquals(
            listOf("Dashboard", "MapNav"),
            HudScreens.carousel(before.joinToString(","), "MapNav,Dashboard")
        )
    }

    @Test fun `never empty, the HUD reads that as no frame yet`() {
        assertEquals(HudScreens.DEFAULT, HudScreens.carousel("Dashboard,Map", "NoSuchScreen"))
        assertEquals(HudScreens.DEFAULT, HudScreens.carousel(" , ", " ,"))
    }
}
