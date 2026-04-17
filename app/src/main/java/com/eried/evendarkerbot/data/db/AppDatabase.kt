package com.eried.evendarkerbot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eried.evendarkerbot.data.model.AlarmRule
import com.eried.evendarkerbot.data.model.AppSettings
import com.eried.evendarkerbot.data.model.TripRecord

@Database(
    entities = [AppSettings::class, TripRecord::class, AlarmRule::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun tripDao(): TripDao
    abstract fun alarmDao(): AlarmDao
}
