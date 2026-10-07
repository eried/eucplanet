package com.eried.eucplanet.ui.navigator

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A "geo:" link or a Maps share into a cold app is consumed inside the view
 * model's init block, through addWaypoint(), which writes every backing flow
 * a waypoint touches. Kotlin runs property initialisers in file order, so any
 * such flow declared below init is still null when init runs and the share
 * crashes the app. This kept every cold share crashing from May until
 * 0.21.0; the fix is declaration order, which nothing else guards.
 */
class RouteBuilderInitOrderTest {

    @Test fun `every flow addWaypoint writes is declared above init`() {
        val src = File("src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt").readText()
        val init = src.indexOf("\n    init {")
        assertTrue("no init block found", init > 0)
        for (flow in listOf("_waypoints", "_lastAddedPresetKind", "_route", "_messages")) {
            val decl = src.indexOf("private val $flow = ")
            assertTrue("$flow is not declared", decl > 0)
            assertTrue("$flow is declared below init and is null while a cold share is consumed", decl < init)
        }
    }
}
