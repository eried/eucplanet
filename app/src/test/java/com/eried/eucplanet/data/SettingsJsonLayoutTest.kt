package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.SettingsLayout
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip guard for the Settings-screen arrangement ([SettingsLayout]). The
 * order and hidden lists serialize as CSV strings (matching dashboardMetricOrder),
 * so a split / join mistake would silently lose the rider's layout. Mirrors the
 * store load path: toJson then fromJson onto a blank base.
 */
class SettingsJsonLayoutTest {

    @Test fun layout_roundTripsThroughStoreLoad() {
        val arranged = AppSettings(
            settingsLayout = SettingsLayout(
                order = listOf("watch", "general", "navigator", "motor"),
                hidden = listOf("motor", "integration"),
            )
        )
        val loaded = SettingsJson.fromJson(SettingsJson.toJson(arranged), AppSettings())

        assertEquals(listOf("watch", "general", "navigator", "motor"), loaded.settingsLayout.order)
        assertEquals(listOf("motor", "integration"), loaded.settingsLayout.hidden)
    }

    @Test fun defaultLayout_roundTripsAsEmpty() {
        val loaded = SettingsJson.fromJson(SettingsJson.toJson(AppSettings()), AppSettings())
        assertEquals(emptyList<String>(), loaded.settingsLayout.order)
        assertEquals(emptyList<String>(), loaded.settingsLayout.hidden)
    }
}
