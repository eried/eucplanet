package com.eried.eucplanet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelProfile

/**
 * Room v45 drops the `app_settings` table. Settings live in DataStore now
 * (see [com.eried.eucplanet.data.store.SettingsStore]); Room is reserved for
 * trips, alarm rules and per-wheel profiles, which change shape rarely and
 * get explicit migrations from this version forward.
 */
@Database(
    entities = [TripRecord::class, AlarmRule::class, WheelProfile::class],
    version = 45,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun alarmDao(): AlarmDao
    abstract fun wheelProfileDao(): WheelProfileDao
}
