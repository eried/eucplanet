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

    @Test fun `both automations ship off`() {
        // Never is the off state: one control, and neither feature starts
        // touching the rider's audio until they choose a condition.
        assertEquals(ApplyWhenIds.NEVER, AppSettings().autoVolumeApplyWhen)
        assertEquals(ApplyWhenIds.NEVER, AppSettings().mediaControl.rateApplyWhen)
    }

    @Test fun `the old switch keeps its meaning`() {
        // On stays on, gated the way the old boolean gated it...
        val wasOn = load(JSONObject().put("autoVolumeEnabled", true).toString())
        assertEquals(ApplyWhenIds.CONNECTED, wasOn.autoVolumeApplyWhen)
        // ...and off stays off, which is the half that matters: nobody's
        // volume starts moving on its own after an update.
        val wasOff = load(JSONObject().put("autoVolumeEnabled", false).toString())
        assertEquals(ApplyWhenIds.NEVER, wasOff.autoVolumeApplyWhen)
    }

    @Test fun `a backup with neither key stays off`() {
        assertEquals(ApplyWhenIds.NEVER, load("{}").autoVolumeApplyWhen)
    }

    @Test fun `the new key wins when both are present`() {
        val json = JSONObject()
            .put("autoVolumeEnabled", true)
            .put("autoVolumeApplyWhen", ApplyWhenIds.RIDING)
            .toString()
        assertEquals(ApplyWhenIds.RIDING, load(json).autoVolumeApplyWhen)
    }

    @Test fun `the gate survives a round trip, for both automations`() {
        val saved = AppSettings(
            autoVolumeApplyWhen = ApplyWhenIds.CONNECTED,
            mediaControl = AppSettings().mediaControl.copy(
                rateApplyWhen = ApplyWhenIds.CONNECTED,
                rateCurve = "0:1.0,25:1.20",
            ),
        )
        val back = load(SettingsJson.toJson(saved).toString())
        assertEquals(ApplyWhenIds.CONNECTED, back.autoVolumeApplyWhen)
        assertEquals(ApplyWhenIds.CONNECTED, back.mediaControl.rateApplyWhen)
        assertEquals("0:1.0,25:1.20", back.mediaControl.rateCurve)
    }

    @Test fun `riding is what a rider picks, not what they inherit`() {
        // Riding is the recommended condition, but it never arrives by
        // migration: an update must not start altering anyone's playback.
        val migrated = load(JSONObject().put("autoVolumeEnabled", true).toString())
        assertEquals(ApplyWhenIds.CONNECTED, migrated.autoVolumeApplyWhen)
        assertEquals(ApplyWhenIds.NEVER, migrated.mediaControl.rateApplyWhen)
    }
}
