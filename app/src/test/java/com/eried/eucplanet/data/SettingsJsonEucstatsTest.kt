package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonEucstatsTest {
    @Test fun eucstatsFields_roundTripThroughBackupJson() {
        val s = AppSettings(
            onlineUploadEnabled = true,
            eucstatsStoreId = "uuid-123",
            eucstatsDisplayName = "Erwin",
            eucstatsFlag = "NO",
            eucstatsConsentPublic = true,
            eucstatsRegisteredAt = 1_700_000_000_000L,
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(s), AppSettings())
        assertEquals("uuid-123", restored.eucstatsStoreId)
        assertEquals("Erwin", restored.eucstatsDisplayName)
        assertEquals("NO", restored.eucstatsFlag)
        assertEquals(true, restored.onlineUploadEnabled)
        assertEquals(true, restored.eucstatsConsentPublic)
    }

    @Test fun stripDeviceBindings_keepsStoreId() {
        val s = AppSettings(eucstatsStoreId = "uuid-123", lastDeviceAddress = "AA:BB")
        val stripped = SettingsJson.stripDeviceBindings(s)
        assertEquals("uuid-123", stripped.eucstatsStoreId) // store_id survives
        assertEquals(null, stripped.lastDeviceAddress)      // device binding removed
    }
}
