package com.eried.eucplanet.hud.ui

import com.eried.eucplanet.hud.protocol.HudScreens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's list of HUD screens, held to the screens the HUD has.
 *
 * The phone names screens by the HUD's enum names and the HUD drops a name it
 * does not know. A screen missing from the phone's list can never be switched
 * on, and one missing here shows in settings and never on the glasses.
 */
class HudScreenIdsTest {

    @Test fun `the phone lists exactly the screens the HUD has`() {
        assertEquals(
            HudUiController.Screen.entries.map { it.name }.toSet(),
            HudScreens.DEFAULT.toSet()
        )
    }

    @Test fun `the default carousel reaches the HUD whole`() {
        val c = HudUiController()
        c.applyEnabledScreens(HudScreens.carousel("", ""))
        assertEquals(HudScreens.DEFAULT, c.screens.map { it.name })
    }
}
