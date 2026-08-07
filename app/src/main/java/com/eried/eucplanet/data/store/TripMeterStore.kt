package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eried.eucplanet.data.model.TripMeterSplit
import com.eried.eucplanet.data.model.TripMeterState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the single [TripMeterState] as one JSON blob in its own DataStore
 * Preferences file. Survives app restart, process death and wheel power-downs;
 * one meter, so a JSON blob is plenty (no Room, no per-split rows). Cleared only
 * when the repository writes an empty state (manual Reset or Stop All).
 *
 * Lives in app-private storage (`<app>/files/datastore/eucplanet_trip_meter.preferences_pb`)
 * and survives app updates. A missing / unreadable blob restores a fresh meter.
 */
@Singleton
class TripMeterStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> get() = context.tripMeterDataStore

    suspend fun load(): TripMeterState {
        val json = dataStore.data.first()[KEY_JSON] ?: return TripMeterState()
        return runCatching { fromJson(JSONObject(json)) }.getOrDefault(TripMeterState())
    }

    suspend fun save(state: TripMeterState) {
        val json = toJson(state).toString()
        dataStore.edit { prefs -> prefs[KEY_JSON] = json }
    }

    companion object {
        private val KEY_JSON = stringPreferencesKey("trip_meter_json")

        fun toJson(state: TripMeterState): JSONObject = JSONObject().apply {
            put("distanceKm", state.distanceKm.toDouble())
            put("activeMs", state.activeMs)
            put("startedAtMs", state.startedAtMs)
            put("splits", JSONArray().apply {
                state.splits.forEach { s ->
                    put(JSONObject().apply {
                        put("index", s.index)
                        put("markDistanceKm", s.markDistanceKm.toDouble())
                        put("cumulativeMs", s.cumulativeMs)
                        put("segmentMs", s.segmentMs)
                        put("segmentAvgKmh", s.segmentAvgKmh.toDouble())
                        put("segmentMaxKmh", s.segmentMaxKmh.toDouble())
                        put("batteryPctAtMark", s.batteryPctAtMark)
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): TripMeterState {
            val arr = o.optJSONArray("splits") ?: JSONArray()
            val splits = ArrayList<TripMeterSplit>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                splits.add(
                    TripMeterSplit(
                        index = s.optInt("index", i + 1),
                        markDistanceKm = s.optDouble("markDistanceKm", 0.0).toFloat(),
                        cumulativeMs = s.optLong("cumulativeMs", 0L),
                        segmentMs = s.optLong("segmentMs", 0L),
                        segmentAvgKmh = s.optDouble("segmentAvgKmh", 0.0).toFloat(),
                        segmentMaxKmh = s.optDouble("segmentMaxKmh", 0.0).toFloat(),
                        batteryPctAtMark = s.optInt("batteryPctAtMark", -1),
                    )
                )
            }
            return TripMeterState(
                distanceKm = o.optDouble("distanceKm", 0.0).toFloat(),
                activeMs = o.optLong("activeMs", 0L),
                startedAtMs = o.optLong("startedAtMs", 0L),
                splits = splits,
            )
        }
    }
}

private val Context.tripMeterDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "eucplanet_trip_meter")
