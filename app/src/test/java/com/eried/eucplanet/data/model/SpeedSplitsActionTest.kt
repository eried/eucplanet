package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SPEED_SPLITS action: where it may be bound, and what it reports.
 *
 * A four-way cycle needs the rider to see which state the tap landed on, so
 * it is a screen action only. The moment somebody makes it eyes-free safe it
 * will appear on Flic, volume keys and the watch, where a rider pressing it
 * three times to get to "brake" has no idea whether they overshot.
 */
class SpeedSplitsActionTest {

    private val spec = ActionCatalog.byKey("SPEED_SPLITS")

    @Test fun `it is in the catalog, under the splits section's own name`() {
        assertNotNull("SPEED_SPLITS must be a catalog action", spec)
        assertEquals(com.eried.eucplanet.R.string.section_accel_splits, spec!!.labelRes)
    }

    @Test fun `it is offered on the dashboard and nowhere eyes-free`() {
        assertTrue(ActionCatalog.keysFor(ActionSurface.DASHBOARD).contains("SPEED_SPLITS"))
        for (surface in listOf(ActionSurface.FLIC, ActionSurface.VOLUME_KEY, ActionSurface.WATCH)) {
            assertFalse("$surface must not offer a four-way cycle blind",
                ActionCatalog.keysFor(surface).contains("SPEED_SPLITS"))
        }
    }

    @Test fun `it reads as active while splits are on, in any direction`() {
        val reader = spec!!.statusReader
        assertNotNull("the tile needs a status to highlight on", reader)
        assertFalse(reader!!(StatusContext(speedSplitsOn = false)))
        assertTrue(reader(StatusContext(speedSplitsOn = true)))
    }

    @Test fun `it needs no wheel`() {
        // A settings write. A rider arms it in the hall.
        assertTrue(spec!!.enabledReader?.invoke(StatusContext(connected = false)) ?: true)
    }

    @Test fun `the dispatcher routes it to the screen, not the physical fallback`() {
        var cycled = 0
        var fellBack: String? = null
        val ui = object : ActionUi {
            override fun openNavigation() {}
            override fun openStudio() {}
            override fun openAbout() {}
            override fun openService() {}
            override fun openTrips() {}
            override fun openWeather() {}
            override fun openCharging() {}
            override fun toggleUnits() {}
            override fun toggleAlarmsMuted() {}
            override fun resetMetrics() {}
            override fun cycleSpeedSplits() { cycled++ }
        }
        dispatchAction("SPEED_SPLITS", ui) { fellBack = it }
        assertEquals(1, cycled)
        assertEquals(null, fellBack)
    }
}
