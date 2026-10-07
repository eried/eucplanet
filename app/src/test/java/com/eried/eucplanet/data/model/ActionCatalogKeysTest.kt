package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action keys are a persistence format, not just identifiers.
 *
 * A rider's dashboard buttons, Flic bindings and service-overlay slots all
 * store the key of the action they were given. Rename one and every rider who
 * customised that button silently loses it: nothing errors, the button is just
 * gone or does nothing.
 *
 * RESET_TRIP is the live example. It is now "Reset metrics" and clears far more
 * than a trip, but it kept its old key for exactly this reason, which is a
 * thing worth writing down where the next person to tidy it will look.
 */
class ActionCatalogKeysTest {

    @Test fun `every catalog key is unique`() {
        val keys = ActionCatalog.all.map { it.key }
        assertEquals("duplicate action keys: $keys", keys.size, keys.toSet().size)
    }

    @Test fun `reset metrics keeps the key it was stored under`() {
        // Renaming this to RESET_METRICS would read better and unbind every
        // rider who put it on a dashboard button. The label is what changed.
        val spec = ActionCatalog.byKey("RESET_TRIP")
        assertNotNull("RESET_TRIP must stay resolvable, it is in stored layouts", spec)
        assertEquals(
            "the label should be the metrics one now",
            com.eried.eucplanet.R.string.action_chip_reset_metrics,
            spec!!.labelRes,
        )
    }

    @Test fun `reset metrics needs no wheel`() {
        // It clears the app's own trip meter and metric history, so a rider
        // can start clean before the wheel is even switched on. It used to be
        // gated on a connection because it only sent a BLE command.
        val spec = ActionCatalog.byKey("RESET_TRIP")!!
        val offline = StatusContext(connected = false)
        // A null reader is "always enabled", which is what dropping the
        // connection gate looks like from here.
        assertTrue("should be usable with no wheel", spec.enabledReader?.invoke(offline) ?: true)
    }

    @Test fun `the keys stored on riders' devices all still resolve`() {
        // Every key this app has ever written into a layout. Adding to the
        // catalog is free; removing or renaming is not.
        listOf(
            "HORN", "LIGHT_TOGGLE", "LOCK_TOGGLE", "SAFETY_TOGGLE", "SAFETY_ON",
            "SAFETY_OFF", "VOICE_ANNOUNCE", "RECORD_TOGGLE", "RECORD_START",
            "RECORD_STOP", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS",
            "OPEN_NAVIGATION", "OPEN_STUDIO", "OPEN_ABOUT", "OPEN_SERVICE",
            "OPEN_TRIPS", "MUTE_ALARMS", "RESET_TRIP", "TOGGLE_UNITS",
        ).forEach {
            assertNotNull("stored key $it no longer resolves", ActionCatalog.byKey(it))
        }
    }
}
