package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.VoiceReportSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** "Reset metrics after triggered report" survives a save and load, and is off by default. */
class SettingsJsonTriggerResetAfterTest {

    @Test fun bothValues_roundTripThroughJson() {
        for (on in listOf(true, false)) {
            val s = AppSettings(voiceReports = VoiceReportSettings(triggerResetAfter = on))
            val json = SettingsJson.toJson(s)
            assertEquals(on, json.getBoolean("triggerReportResetAfter"))
            assertEquals(on, SettingsJson.fromJson(json, AppSettings()).voiceReports.triggerResetAfter)
        }
    }

    @Test fun missingKey_isOff() {
        assertFalse(SettingsJson.fromJson(JSONObject(), AppSettings()).voiceReports.triggerResetAfter)
    }
}
