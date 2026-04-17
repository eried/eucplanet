package com.eried.eucplanet.di

import android.content.Context
import androidx.room.Room
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.db.AppDatabase
import com.eried.eucplanet.data.db.SettingsDao
import com.eried.eucplanet.data.db.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "eucplanet.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideAlarmDao(db: AppDatabase): AlarmDao = db.alarmDao()
}
