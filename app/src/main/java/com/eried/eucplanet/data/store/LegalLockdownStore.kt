package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Armed flag plus the MD5 of the manufacturer code. */
data class LockdownState(
    val armed: Boolean = false,
    val codeHash: String = ""
)

/**
 * Legal Mode Lockdown state, deliberately NOT an [com.eried.eucplanet.data.model.AppSettings]
 * field and deliberately not in the `eucplanet_settings` store.
 *
 * Everything in AppSettings flows through [SettingsJson] and SyncManager, and
 * the restore path overwrites the device's current values from the payload. A
 * lockdown flag living there would be cleared by restoring any older backup,
 * which is a one-tap bypass of the whole feature. Its own store makes that
 * impossible by construction instead of by remembering to strip a field in two
 * places.
 *
 * The second reason is the promise the feature makes to the rider: arming and
 * disarming must not change a single setting. With the state out here, the arm
 * and disarm paths never call SettingsRepository.update() at all, so the promise
 * is structural rather than something we have to keep testing for.
 *
 * Excluded from Android auto-backup and device transfer in `backup_rules.xml`
 * and `data_extraction_rules.xml`, so the lock does not ride a cloud backup onto
 * a new phone and a reinstall is genuinely clean.
 */
class LegalLockdownStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.lockdownDataStore

    val state: Flow<LockdownState> = dataStore.data.map { prefs ->
        LockdownState(
            armed = prefs[KEY_ARMED] ?: false,
            codeHash = prefs[KEY_HASH].orEmpty()
        )
    }

    suspend fun get(): LockdownState = state.first()

    suspend fun set(armed: Boolean, codeHash: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ARMED] = armed
            prefs[KEY_HASH] = codeHash
        }
    }

    private companion object {
        val KEY_ARMED = booleanPreferencesKey("armed")
        val KEY_HASH = stringPreferencesKey("codeHash")
    }
}

private val Context.lockdownDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "legal_lockdown")
