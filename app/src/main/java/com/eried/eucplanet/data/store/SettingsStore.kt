package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Persists the full [AppSettings] object as a single JSON blob in DataStore
 * Preferences. Adding a new setting field is a one-line change to the data
 * class — no DataStore key, no DB migration, no risk of losing rider state.
 *
 * The schema-compat trick is that [SettingsJson.fromJson] reads each field
 * with `optX(name, default)`, so unknown payloads (older or newer than the
 * running app) just keep the in-memory default. Forward / backward.
 *
 * Lives in app-private storage (`<app>/files/datastore/eucplanet_settings.preferences_pb`)
 * and survives app updates. The legacy Room `app_settings` row is migrated
 * across once, the first time this store is read after the upgrade.
 *
 * Settings here, Room over there: trips, alarm rules and per-wheel profiles
 * stay in Room with real migrations because they don't churn nearly as much
 * as the toggle list does.
 */
class SettingsStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs -> readSettings(prefs) }

    suspend fun get(): AppSettings = readSettings(dataStore.data.first())

    suspend fun update(settings: AppSettings) {
        val json = SettingsJson.toJson(settings).toString()
        dataStore.edit { prefs -> prefs[KEY_JSON] = json }
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
     * starts metric. Existing users never reach this branch — their blob is
     * already present — so they are never flipped.
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

    /** US, Liberia and Myanmar are the three imperial-system countries. */
    private fun isImperialLocale(): Boolean =
        java.util.Locale.getDefault().country in setOf("US", "LR", "MM")

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

private val Context.settingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "eucplanet_settings")
