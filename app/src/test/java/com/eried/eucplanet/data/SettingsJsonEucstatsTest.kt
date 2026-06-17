package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsJsonEucstatsTest {

    /**
     * After the eucstats slim-down: `onlineUploadEnabled` is the only eucstats
     * field we serialize. Display name / flag / consent / registered-at all
     * live on the server, and the store_id lives in `eucstats_riderid.txt`,
     * so none of them belong in the settings backup JSON.
     */
    @Test fun onlineUploadEnabled_roundTripsThroughBackupJson() {
        val on = AppSettings(onlineUploadEnabled = true)
        val restoredOn = SettingsJson.fromJson(SettingsJson.toJson(on), AppSettings())
        assertEquals(true, restoredOn.onlineUploadEnabled)

        val off = AppSettings(onlineUploadEnabled = false)
        val restoredOff = SettingsJson.fromJson(SettingsJson.toJson(off), AppSettings())
        assertEquals(false, restoredOff.onlineUploadEnabled)
    }

    /** The serialized JSON must not carry any of the dropped eucstats keys —
     *  a stale field in a backup would re-introduce the cached profile data
     *  we explicitly moved to the server. */
    @Test fun backupJson_doesNotMentionDroppedKeys() {
        val s = AppSettings(onlineUploadEnabled = true)
        val json = SettingsJson.toJson(s).toString()
        listOf(
            "eucstatsStoreId",
            "eucstatsDisplayName",
            "eucstatsFlag",
            "eucstatsConsentPublic",
            "eucstatsRegisteredAt",
        ).forEach { key ->
            assertFalse("settings backup must not carry $key", json.contains(key))
        }
    }
}
