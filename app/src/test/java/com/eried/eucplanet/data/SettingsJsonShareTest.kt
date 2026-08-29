package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ShareSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonShareTest {
    @Test fun shareSettingsRoundTrip() {
        val s = AppSettings().copy(share = ShareSettings(trailMinutes = 12, shareStatsDefault = false,
            lastIdentityMode = "PROFILE", lastSessionName = "Erwin", relayUrl = "wss://x.example"))
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(s.share, back.share)
    }
    @Test fun shareSettingsDefaultsOnEmptyJson() {
        assertEquals(ShareSettings(), SettingsJson.fromJson(JSONObject("{}")).share)
    }
}
