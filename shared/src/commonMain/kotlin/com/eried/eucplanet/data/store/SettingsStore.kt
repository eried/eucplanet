package com.eried.eucplanet.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.eried.eucplanet.platform.JSONObject

/**
 * Persists the full [AppSettings] object as a single JSON blob in DataStore
 * Preferences. Adding a new setting field is a one-line change to the data
 * class, no DataStore key, no DB migration, no risk of losing rider state.
 *
 * The schema-compat trick is that [SettingsJson.fromJson] reads each field
 * with `optX(name, default)`, so unknown payloads (older or newer than the
 * running app) just keep the in-memory default. Forward / backward.
 *
 * Lives in app-private storage and survives app updates: on Android
 * `<app>/files/datastore/eucplanet_settings.preferences_pb` (SettingsStoreAndroid.kt),
 * on iOS the same file name in the app's Documents folder. The legacy Room `app_settings` row is migrated
 * across once, the first time this store is read after the upgrade.
 *
 * Settings here, Room over there: trips, alarm rules and per-wheel profiles
 * stay in Room with real migrations because they don't churn nearly as much
 * as the toggle list does.
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    /** The first-run unit guess, see [seedDefaultsIfAbsent]. */
    private val isImperialLocale: () -> Boolean,
) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs -> readSettings(prefs) }

    suspend fun get(): AppSettings = readSettings(dataStore.data.first())

    suspend fun update(settings: AppSettings) {
        val json = SettingsJson.toJson(settings).toString()
        dataStore.edit { prefs -> prefs[KEY_JSON] = json }
    }

    /**
     * Change a few fields without losing a concurrent change to the others.
     *
     * Every setting lives in one JSON blob, so a write is a read, a copy and a
     * write of the whole thing. Doing the read outside the transaction made two
     * quick changes race: both read the same state, both wrote the whole blob,
     * and whichever landed last silently undid the other. The HUD IP field
     * writes on every keystroke, which is how a rider who typed a full address
     * ended up with `10.240.` saved - an early keystroke landing last. (The
     * same reordering is what produced the "192 appears as 921" report in the
     * settings screen.)
     *
     * DataStore serialises the transforms it is given, so doing the read inside
     * [androidx.datastore.preferences.core.edit] makes each change atomic and
     * ordered against every other change.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_JSON] = SettingsJson.toJson(transform(readSettings(prefs))).toString()
        }
    }

    /**
     * One-shot import: if DataStore has no settings yet, write [imported]
     * verbatim. No-op on subsequent calls so re-running the legacy-Room
     * migration is safe.
     */
    suspend fun seedIfAbsent(imported: AppSettings) {
        dataStore.edit { prefs ->
            if (prefs[KEY_JSON] == null) {
                prefs[KEY_JSON] = SettingsJson.toJson(imported).toString()
            }
        }
    }

    /**
     * Genuine first-run hook. Runs after the legacy-Room migration: if
     * DataStore is STILL empty here, this is a fresh install (no old Room
     * row, no prior DataStore blob). Seed the three per-unit fields from the
     * device locale so imperial-locale users start in imperial; everyone else
     * starts metric. Existing users never reach this branch, their blob is
     * already present, so they are never flipped.
     */
    suspend fun seedDefaultsIfAbsent() {
        dataStore.edit { prefs ->
            if (prefs[KEY_JSON] == null) {
                val imperial = isImperialLocale()
                val seeded = AppSettings(
                    unitSpeed = if (imperial) "mph" else "kmh",
                    unitDistance = if (imperial) "mi" else "km",
                    unitTemp = if (imperial) "F" else "C"
                )
                prefs[KEY_JSON] = SettingsJson.toJson(seeded).toString()
            }
        }
    }

    private fun readSettings(prefs: Preferences): AppSettings {
        val json = prefs[KEY_JSON] ?: return AppSettings()
        return runCatching {
            SettingsJson.fromJson(JSONObject(json))
        }.getOrElse { AppSettings() }
    }

    companion object {
        private val KEY_JSON = stringPreferencesKey("app_settings_json")
    }
}
