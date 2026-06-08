package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the voiceAnnounceWhen setting against breaking existing settings
 * files: the field must survive a save/load round-trip, migrate cleanly from
 * the old voiceOnlyWhenConnected boolean, and never wipe neighbouring fields.
 */
class SettingsJsonVoiceAnnounceTest {

    @Test fun voiceAnnounceWhen_roundTripsThroughJson() {
        for (mode in listOf("ALWAYS", "CONNECTED", "RIDING")) {
            val restored = SettingsJson.fromJson(
                SettingsJson.toJson(AppSettings(voiceAnnounceWhen = mode)),
                AppSettings()
            )
            assertEquals(mode, restored.voiceAnnounceWhen)
        }
    }

    @Test fun legacyBoolean_true_migratesToConnected_andKeepsOtherFields() {
        // A pre-feature settings file only has the old boolean. It must still
        // load, migrate to CONNECTED, and leave neighbouring fields intact.
        val legacy = JSONObject()
            .put("voiceOnlyWhenConnected", true)
            .put("voiceIntervalSeconds", 45)
        val restored = SettingsJson.fromJson(legacy, AppSettings())
        assertEquals("CONNECTED", restored.voiceAnnounceWhen)
        assertEquals(45, restored.voiceIntervalSeconds)
    }

    @Test fun legacyBoolean_false_migratesToAlways() {
        val legacy = JSONObject().put("voiceOnlyWhenConnected", false)
        assertEquals("ALWAYS", SettingsJson.fromJson(legacy, AppSettings()).voiceAnnounceWhen)
    }

    @Test fun missingKeys_fallBackToModelDefault() {
        // Neither the new key nor the legacy boolean present -> model default.
        assertEquals("RIDING", SettingsJson.fromJson(JSONObject(), AppSettings()).voiceAnnounceWhen)
    }
}
