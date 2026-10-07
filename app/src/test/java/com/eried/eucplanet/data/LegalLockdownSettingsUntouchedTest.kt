package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The central promise of Legal Mode Lockdown: arming and disarming change no
 * rider setting.
 *
 * Because the lock lives in its own DataStore rather than in AppSettings, the
 * promise reduces to a structural claim, which is what these assert. If someone
 * later moves the flag into AppSettings, restoring an older settings backup
 * would silently disarm the lock, and this test is what fails first.
 */
class LegalLockdownSettingsUntouchedTest {

    @Test
    fun `lockdown state never appears in the settings payload`() {
        val json = SettingsJson.toJson(AppSettings()).toString().lowercase()
        assertFalse("lockdown state must not be serialised into AppSettings",
            json.contains("lockdown"))
        assertFalse("the manufacturer code hash must never reach a settings file",
            json.contains("codehash"))
    }

    @Test
    fun `no AppSettings field mentions lockdown`() {
        val fieldNames = AppSettings::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { it.contains("lockdown") })
    }

    @Test
    fun `settings round trip is unaffected`() {
        val original = AppSettings()
        val restored = SettingsJson.fromJson(SettingsJson.toJson(original))
        assertEquals(original, restored)
    }
}
