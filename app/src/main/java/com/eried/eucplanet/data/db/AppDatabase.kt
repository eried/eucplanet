package com.eried.eucplanet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelProfile

@Database(
    entities = [AppSettings::class, TripRecord::class, AlarmRule::class, WheelProfile::class],
    version = 43,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun tripDao(): TripDao
    abstract fun alarmDao(): AlarmDao
    abstract fun wheelProfileDao(): WheelProfileDao
}
