package com.eried.eucplanet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord

@Database(
    entities = [AppSettings::class, TripRecord::class, AlarmRule::class],
    version = 23,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun tripDao(): TripDao
    abstract fun alarmDao(): AlarmDao
}
