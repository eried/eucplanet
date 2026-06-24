package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
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
}
