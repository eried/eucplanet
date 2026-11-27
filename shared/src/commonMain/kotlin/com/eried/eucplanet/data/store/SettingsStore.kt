package com.eried.eucplanet.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.eried.eucplanet.platform.JSONObject
import okio.IOException
import com.eried.eucplanet.platform.Log
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
    /** The first-run units from where the phone is, see [seedDefaultsIfAbsent]. */
    private val startingUnits: () -> DefaultUnits.Units,
    // Told when a save fails, which in practice means the phone is out of
    // storage.
    private val onWriteFailed: (IOException) -> Unit = {},
    // Told, once, when the stored settings could not be read and the app
    // carries on from the defaults.
    private val onReset: () -> Unit = {},
    // Whether a settings file was there when the app started. A damaged file
    // does not always fail to read: its bytes can pass for a file with
    // nothing in it. A file that was there and holds no settings is that
    // case; on a first install there is no file at all. Each phone looks at
    // its own file, see [FILE_NAME].
    private val fileWasThere: Boolean = false,
) {

    private val resetLock = SynchronizedObject()
    private var resetReported = false

    private fun reportReset(how: String) {
        synchronized(resetLock) {
            if (resetReported) return
            resetReported = true
        }
        Log.e("SettingsStore", "Stored settings unreadable ($how), starting from defaults")
        onReset()
    }

    init {
        whenSettingsFileDamaged = { reportReset("file damaged") }
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs -> readSettings(prefs) }

    suspend fun get(): AppSettings = readSettings(dataStore.data.first())

    /**
     * Every write goes through here. Out of storage, DataStore throws an
     * IOException, and since saves run in plain coroutines that closed the
     * whole app on a tap of any setting. DataStore writes a new file and swaps
     * it in, so a failed save leaves the settings that were there intact.
     */
    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (e: IOException) {
            Log.e("SettingsStore", "Settings not saved", e)
            onWriteFailed(e)
        }
    }

    suspend fun update(settings: AppSettings) {
        val json = SettingsJson.toJson(settings).toString()
        write { prefs -> prefs[KEY_JSON] = json }
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
        write { prefs ->
            prefs[KEY_JSON] = SettingsJson.toJson(transform(readSettings(prefs))).toString()
        }
    }

    /**
     * One-shot import: if DataStore has no settings yet, write [imported]
     * verbatim. No-op on subsequent calls so re-running the legacy-Room
     * migration is safe.
     */
    suspend fun seedIfAbsent(imported: AppSettings) {
        write { prefs ->
            if (prefs[KEY_JSON] == null) {
                if (fileWasThere) reportReset("file held no settings")
                prefs[KEY_JSON] = SettingsJson.toJson(imported).toString()
            }
        }
    }

    /**
     * Genuine first-run hook. Runs after the legacy-Room migration: if
     * DataStore is STILL empty here, this is a fresh install (no old Room
     * row, no prior DataStore blob). Seed the per-unit fields from
     * where the phone is ([DefaultUnits]): miles and Fahrenheit, miles and
     * Celsius, or metric. Existing users never reach this branch, their blob
     * is already present, so they are never flipped.
     */
    suspend fun seedDefaultsIfAbsent() {
        write { prefs ->
            if (prefs[KEY_JSON] == null) {
                if (fileWasThere) reportReset("file held no settings")
                val units = startingUnits()
                val seeded = AppSettings(
                    unitSpeed = units.speed,
                    unitDistance = units.distance,
                    unitTemp = units.temp,
                    unitAltitude = units.altitude
                )
                prefs[KEY_JSON] = SettingsJson.toJson(seeded).toString()
                // A fresh install starts on the current defaults.
                prefs[KEY_DEFAULTS_REV] = NewDefaults.REV
            }
            // A default that changed reaches an existing install once, here,
            // in the transaction the start already runs. See NewDefaults.
            val rev = prefs[KEY_DEFAULTS_REV] ?: 0
            if (rev < NewDefaults.REV) {
                val before = readSettings(prefs)
                val after = NewDefaults.upgrade(before, rev)
                if (after != before) prefs[KEY_JSON] = SettingsJson.toJson(after).toString()
                prefs[KEY_DEFAULTS_REV] = NewDefaults.REV
            }
        }
    }

    // The last blob read and what it parsed to. Every read used to parse the
    // whole blob again, some three hundred fields, and there are well over a
    // hundred places that read: a dozen of them in the first seconds of a
    // start, then every upload pass and every return to the app. The blob only
    // changes when a setting is saved, and then it is another string.
    // AppSettings is immutable, so one instance can be handed to everyone.
    @Volatile private var lastRead: Pair<String, AppSettings>? = null

    private fun readSettings(prefs: Preferences): AppSettings {
        val json = prefs[KEY_JSON] ?: return AppSettings()
        lastRead?.let { (text, parsed) -> if (text == json) return parsed }
        val parsed = runCatching {
            SettingsJson.fromJson(JSONObject(json))
        }.getOrElse {
            reportReset("contents damaged")
            AppSettings()
        }
        lastRead = json to parsed
        return parsed
    }

    companion object {
        /** The DataStore's name, so the start can ask whether its file exists. */
        const val FILE_NAME = "eucplanet_settings"
        private val KEY_JSON = stringPreferencesKey("app_settings_json")
        /** Which revision of [NewDefaults] this install has taken. */
        private val KEY_DEFAULTS_REV = intPreferencesKey("defaults_rev")
    }
}

/**
 * For the settings file's corruption handler on each phone: the rider is told,
 * since the app otherwise opens on the welcome tour with every choice gone and
 * no word about why.
 */
fun settingsFileDamaged() = whenSettingsFileDamaged()

/** Set by the one [SettingsStore]; the file's handler has no other way to it. */
@Volatile private var whenSettingsFileDamaged: () -> Unit = {}
