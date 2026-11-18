package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The settings file, `<app>/files/datastore/eucplanet_settings.preferences_pb`. */
val Context.settingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "eucplanet_settings")

/**
 * This phone's two signals, the network's country and the region setting's,
 * handed to [DefaultUnits.pick], which holds the rule.
 */
fun androidStartingUnits(context: Context): DefaultUnits.Units {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
    val network = runCatching { tm?.networkCountryIso }.getOrNull().orEmpty()
    return DefaultUnits.pick(network, java.util.Locale.getDefault().country)
}
