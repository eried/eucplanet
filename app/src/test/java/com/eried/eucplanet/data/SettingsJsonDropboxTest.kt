package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the Dropbox-link load bug: the token persisted by
 * [SettingsJson.toJson] must read back through [SettingsJson.fromJson] on the
 * normal load path (default blank base), the way SettingsStore reads it on
 * every settings emit. A prior version hardcoded the dropbox fields to `base`,
 * so the saved token was dropped on the next read and the link never "took".
 */
class SettingsJsonDropboxTest {

    @Test fun dropboxLink_roundTripsThroughStoreLoad() {
        val linked = AppSettings(
            dropboxAccessToken = "sl.ABC123",
            dropboxRefreshToken = "rt.XYZ789",
            dropboxAccessTokenExpiresAt = 1_900_000_000_000L,
            dropboxAccountLabel = "rider@example.com",
            dropboxLastSyncAt = 1_800_000_000_000L,
        )
        // Mirror SettingsStore.readSettings: fromJson with the DEFAULT blank base.
        val loaded = SettingsJson.fromJson(SettingsJson.toJson(linked), AppSettings())

        assertEquals("sl.ABC123", loaded.dropboxAccessToken)
        assertEquals("rt.XYZ789", loaded.dropboxRefreshToken)
        assertEquals(1_900_000_000_000L, loaded.dropboxAccessTokenExpiresAt)
        assertEquals("rider@example.com", loaded.dropboxAccountLabel)
        assertEquals(1_800_000_000_000L, loaded.dropboxLastSyncAt)
    }

    /** A backup file never carries the token (stripDeviceBindings blanks it),
     *  so restoring onto a linked device must not wipe the live link -- the
     *  restore call site re-applies the current values. Here we prove the
     *  backup JSON itself is blank, which is the precondition for that. */
    @Test fun strippedBackup_carriesNoLiveToken() {
        val linked = AppSettings(dropboxAccessToken = "sl.SECRET", dropboxAccountLabel = "me@x.com")
        val backup = SettingsJson.toJson(SettingsJson.stripDeviceBindings(linked))
        val loaded = SettingsJson.fromJson(backup, AppSettings())
        assertEquals("", loaded.dropboxAccessToken)
        assertEquals("", loaded.dropboxAccountLabel)
    }

    @Test
    fun dropboxSettingsBaseHash_roundTrips_and_is_stripped() {
        val s = AppSettings().copy(dropboxSettingsBaseHash = "abc123")
        // round-trips through JSON
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals("abc123", back.dropboxSettingsBaseHash)
        // stripped for the portable/upload copy
        assertEquals("", SettingsJson.stripDeviceBindings(s).dropboxSettingsBaseHash)
    }

    @Test
    fun applyPortable_keepsThisPhonesDeviceFields_takesPreferences() {
        val current = AppSettings().copy(
            lastDeviceAddress = "AA:BB", dropboxAccessToken = "tok",
            syncFolderUri = "content://x", dropboxSettingsBaseHash = "base",
            alarmSpeedKmh = 40f,
        )
        // A portable payload from another phone: device fields stripped, a different preference.
        val portable = SettingsJson.stripDeviceBindings(current.copy(alarmSpeedKmh = 55f))
        val json = JSONObject(SettingsJson.toJson(portable).toString())
        val merged = SettingsJson.applyPortable(json, current)
        assertEquals(55f, merged.alarmSpeedKmh)               // preference taken from payload
        assertEquals("AA:BB", merged.lastDeviceAddress)       // device field kept (fromJson base fallback)
        assertEquals("tok", merged.dropboxAccessToken)        // Dropbox token kept
        assertEquals("content://x", merged.syncFolderUri)     // backup folder kept
        assertEquals("base", merged.dropboxSettingsBaseHash)  // sync baseline kept
    }

    /** Legacy Dropbox users' first post-upgrade download is a RAW settings.json
     *  (pre-two-way-sync code uploaded unstripped), so it can carry another
     *  phone's device bindings with real, populated values -- not absent keys.
     *  applyPortable must force THIS phone's own bindings regardless, not
     *  merely rely on fromJson's base-fallback for absent keys. */
    @Test
    fun applyPortable_keepsDeviceFields_evenFromARawUnstrippedPayload() {
        val current = AppSettings().copy(
            lastDeviceAddress = "AA:BB", radarAddress = "CC:DD", syncFolderUri = "content://mine",
            flic1Address = "EE:FF", externalGpsAddress = "GG:HH", dropboxAccessToken = "tok",
            dropboxSettingsBaseHash = "base", alarmSpeedKmh = 40f,
        )
        // A legacy RAW remote: device fields POPULATED with another phone's values, plus a changed preference.
        val raw = current.copy(
            lastDeviceAddress = "OTHER-1", radarAddress = "OTHER-2", syncFolderUri = "content://theirs",
            flic1Address = "OTHER-3", externalGpsAddress = "OTHER-4", dropboxAccessToken = "OTHER-TOK",
            alarmSpeedKmh = 55f,
        )
        val json = JSONObject(SettingsJson.toJson(raw).toString()) // NOT stripped
        val merged = SettingsJson.applyPortable(json, current)
        assertEquals(55f, merged.alarmSpeedKmh)                 // preference taken
        assertEquals("AA:BB", merged.lastDeviceAddress)         // device fields kept from current, not the raw file
        assertEquals("CC:DD", merged.radarAddress)
        assertEquals("content://mine", merged.syncFolderUri)
        assertEquals("EE:FF", merged.flic1Address)
        assertEquals("GG:HH", merged.externalGpsAddress)
        assertEquals("tok", merged.dropboxAccessToken)
        assertEquals("base", merged.dropboxSettingsBaseHash)
    }
}
