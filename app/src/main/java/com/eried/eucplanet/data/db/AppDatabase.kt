package com.eried.eucplanet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelProfile

/**
 * Room v45 dropped the `app_settings` table (settings live in DataStore now,
 * see [com.eried.eucplanet.data.store.SettingsStore]). v46 adds the per-wheel
 * `reverseSpeedDirection` flag for Begode / Veteran wheels with inverted
 * motor wiring. v48 adds the per-alarm `leadTimeMs` for predictive alarms.
 * Room is reserved for trips, alarm rules and per-wheel profiles, which
 * change shape rarely and get explicit migrations.
 */
@Database(
    entities = [TripRecord::class, AlarmRule::class, WheelProfile::class],
    version = 50,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun alarmDao(): AlarmDao
    abstract fun wheelProfileDao(): WheelProfileDao
}
