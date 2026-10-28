package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.data.model.OfflineRoutingSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/** Offline routing keeps its mode, server and Wi-Fi switch across a restart. */
class OfflineRoutingSettingsJsonTest {

    @Test fun defaults_areOnlineFirst_fromBrouterDe() {
        val d = AppSettings().offlineRouting
        assertEquals(OfflineRoutingMode.FALLBACK, d.mode)
        assertEquals("https://brouter.de/brouter", d.serverUrl)
        assertEquals(false, d.wifiAutoUpdate)
    }

    @Test fun roundTrip() {
        val saved = AppSettings(
            offlineRouting = OfflineRoutingSettings(
                mode = OfflineRoutingMode.PREFER,
                serverUrl = "https://example.org/tiles",
                wifiAutoUpdate = true,
            )
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(saved), AppSettings())
        assertEquals(saved.offlineRouting, restored.offlineRouting)
    }

    @Test fun missingGroup_givesDefaults() {
        val j = SettingsJson.toJson(AppSettings()).apply { remove("offlineRouting") }
        val restored = SettingsJson.fromJson(j, AppSettings())
        assertEquals(OfflineRoutingSettings(), restored.offlineRouting)
    }

    @Test fun unknownMode_isOnlineFirst() {
        val j = SettingsJson.toJson(AppSettings())
        j.put("offlineRouting", JSONObject().put("mode", "SOMETHING"))
        val restored = SettingsJson.fromJson(j, AppSettings())
        assertEquals(OfflineRoutingMode.FALLBACK, restored.offlineRouting.mode)
    }
}
