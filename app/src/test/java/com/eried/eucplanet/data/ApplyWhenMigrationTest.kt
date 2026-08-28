package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.ApplyWhenIds
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/**
 * Auto-volume's "only when a wheel is connected" boolean became a three-way
 * gate shared with the playback rate. A rider who set that boolean must keep
 * what it meant: silently moving them onto the new default would change when
 * their volume moves, which is exactly the kind of change nobody attributes
 * to an update.
 */
class ApplyWhenMigrationTest {

    private fun load(json: String) = SettingsJson.fromJson(JSONObject(json), AppSettings())

    @Test fun `a new install rides`() {
        assertEquals(ApplyWhenIds.RIDING, AppSettings().autoVolumeApplyWhen)
        assertEquals(ApplyWhenIds.RIDING, AppSettings().mediaControl.rateApplyWhen)
    }

    @Test fun `the old boolean keeps its meaning`() {
        val gated = load(JSONObject().put("autoVolumeOnlyWhenConnected", true).toString())
        assertEquals(ApplyWhenIds.CONNECTED, gated.autoVolumeApplyWhen)

        val ungated = load(JSONObject().put("autoVolumeOnlyWhenConnected", false).toString())
        assertEquals(ApplyWhenIds.NEVER, ungated.autoVolumeApplyWhen)
    }

    @Test fun `a backup with neither key gets the new default`() {
        assertEquals(ApplyWhenIds.RIDING, load("{}").autoVolumeApplyWhen)
    }

    @Test fun `the new key wins when both are present`() {
        val json = JSONObject()
            .put("autoVolumeOnlyWhenConnected", true)
            .put("autoVolumeApplyWhen", ApplyWhenIds.RIDING)
            .toString()
        assertEquals(ApplyWhenIds.RIDING, load(json).autoVolumeApplyWhen)
    }

    @Test fun `the gate survives a round trip, for both automations`() {
        val saved = AppSettings(
            autoVolumeApplyWhen = ApplyWhenIds.CONNECTED,
            mediaControl = AppSettings().mediaControl.copy(
                rateEnabled = true,
                rateApplyWhen = ApplyWhenIds.NEVER,
                rateCurve = "0:1.0,25:1.20",
            ),
        )
        val back = load(SettingsJson.toJson(saved).toString())
        assertEquals(ApplyWhenIds.CONNECTED, back.autoVolumeApplyWhen)
        assertEquals(ApplyWhenIds.NEVER, back.mediaControl.rateApplyWhen)
        assertEquals("0:1.0,25:1.20", back.mediaControl.rateCurve)
        assertEquals(true, back.mediaControl.rateEnabled)
    }

    @Test fun `the rate ships off`() {
        assertEquals(false, AppSettings().mediaControl.rateEnabled)
    }
}
