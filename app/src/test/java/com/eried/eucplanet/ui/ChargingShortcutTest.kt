package com.eried.eucplanet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dashboard battery icon's two speeds: tap opens the charging monitor,
 * hold opens it with the details flyout already up - the rider who came for
 * the graphs skips the extra tap.
 */
class ChargingShortcutTest {

    private val dash = File("src/main/java/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt").readText()
    private val nav = File("src/main/java/com/eried/eucplanet/ui/navigation/NavGraph.kt").readText()
    private val screen = File("src/main/java/com/eried/eucplanet/ui/charging/ChargingMonitorScreen.kt").readText()

    @Test fun `the battery icon can be held`() {
        val icon = dash.substringAfter("if (showChargingIcon)").take(900)
        assertTrue("the icon lost its long-press", icon.contains("onLongClick = onNavigateToChargingDetails"))
        assertTrue("the tap no longer opens the monitor", icon.contains("onClick = onNavigateToCharging"))
    }

    @Test fun `the hold travels through the route as a flag`() {
        assertTrue(nav.contains("charging_monitor?details={details}"))
        assertTrue("the long-press does not request the flyout",
            nav.contains("Screen.ChargingMonitor.createRoute(details = true)"))
        assertTrue("the flag never reaches the screen",
            nav.contains("initialDetailsOpen = backStackEntry.arguments?.getBoolean(\"details\") == true"))
    }

    @Test fun `the screen seeds its flyout from the flag`() {
        assertTrue(screen.contains("var showSheet by remember { mutableStateOf(initialDetailsOpen) }"))
    }
}
