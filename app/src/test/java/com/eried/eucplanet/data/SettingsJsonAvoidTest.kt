package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route-avoidance flags must default off, survive a save/load round-trip,
 * and not disturb neighbouring nav settings.
 */
class SettingsJsonAvoidTest {

    @Test fun avoidances_defaultOff() {
        val d = AppSettings()
        assertFalse(d.navAvoidHighways)
        assertFalse(d.navAvoidTolls)
        assertFalse(d.navAvoidFerries)
        assertFalse(d.navAvoidUnpaved)
    }

    @Test fun avoidances_roundTrip() {
        val saved = AppSettings(
            navAvoidHighways = true,
            navAvoidTolls = false,
            navAvoidFerries = true,
            navAvoidUnpaved = true,
            navArrivalRadiusM = 33,
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(saved), AppSettings())
        assertTrue(restored.navAvoidHighways)
        assertFalse(restored.navAvoidTolls)
        assertTrue(restored.navAvoidFerries)
        assertTrue(restored.navAvoidUnpaved)
        // Neighbouring field untouched.
        assertEquals(33, restored.navArrivalRadiusM)
    }

    @Test fun avoidances_missingKeys_fallBackToDefault() {
        // A pre-feature settings file has none of the keys; load must not crash
        // and must default them all off.
        val restored = SettingsJson.fromJson(org.json.JSONObject(), AppSettings())
        assertFalse(restored.navAvoidHighways)
        assertFalse(restored.navAvoidFerries)
    }
}
