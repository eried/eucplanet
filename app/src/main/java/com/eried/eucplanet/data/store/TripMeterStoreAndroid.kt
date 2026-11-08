package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The trip meter file, `<app>/files/datastore/eucplanet_trip_meter.preferences_pb`. */
val Context.tripMeterDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "eucplanet_trip_meter")
