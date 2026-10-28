package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The settings file, `<app>/files/datastore/eucplanet_settings.preferences_pb`. */
val Context.settingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "eucplanet_settings")

/**
 * Imperial only when BOTH the cell-network country AND the locale country
 * are one of the three imperial-system countries (US, Liberia, Myanmar).
 * The two-signal rule prevents bilingual mis-detection: a Norwegian who
 * sets the device language to "English (United States)" has locale.country
 * = "US" alone, but their carrier is Telenor (NO) so the network disagrees
 * and we correctly default to metric. Blank / unavailable signals (Wi-Fi
 * tablet, emulator without a SIM) count as "not imperial" so the default
 * stays metric -- safer to be wrong toward metric and let the rider flip
 * units once than to silently force imperial on the wrong continent.
 */
fun androidImperialLocale(context: Context): Boolean {
    val imperialCountries = setOf("US", "LR", "MM")
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
    val network = tm?.networkCountryIso?.uppercase().orEmpty()
    val locale = java.util.Locale.getDefault().country.uppercase()
    return network in imperialCountries && locale in imperialCountries
}
