package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMediaControlTest {

    @Test
    fun requireExternalOutputSurvivesRoundTrip() {
        val settings = AppSettings().copy(
            mediaControl = AppSettings().mediaControl.copy(requireExternalOutput = true)
        )
        val roundTripped =
            SettingsJson.fromJson(JSONObject(SettingsJson.toJson(settings).toString()))
        assertTrue(roundTripped.mediaControl.requireExternalOutput)
    }

    @Test
    fun requireExternalOutputDefaultsTrueOnEmptyJson() {
        // New installs default to headphones/Bluetooth-only resume.
        val roundTripped = SettingsJson.fromJson(JSONObject("{}"))
        assertTrue(roundTripped.mediaControl.requireExternalOutput)
    }

    @Test
    fun requireExternalOutputFalseSurvivesRoundTrip() {
        // A rider who turned it off keeps it off, despite the true default.
        val settings = AppSettings().copy(
            mediaControl = AppSettings().mediaControl.copy(requireExternalOutput = false)
        )
        val roundTripped =
            SettingsJson.fromJson(JSONObject(SettingsJson.toJson(settings).toString()))
        assertEquals(false, roundTripped.mediaControl.requireExternalOutput)
    }
}
