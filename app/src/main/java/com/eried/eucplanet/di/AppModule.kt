package com.eried.eucplanet.di

import android.content.Context
import android.util.Log
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

    private const val DB_NAME = "eucplanet.db"
    private const val TAG = "AppModule"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return openOrRecover(context)
    }

    /**
     * Build the Room database with destructive fallback on both upgrade and
     * downgrade. If the first open still fails (the usual culprit being a
     * schema identity-hash mismatch at the same version number, which Room's
     * fallback machinery does NOT cover), delete the database file and
     * rebuild from scratch. The user loses local settings in that case, which
     * is a tiny cost compared to an unrecoverable crash on every cold start.
     */
    private fun openOrRecover(context: Context): AppDatabase {
        val first = buildDb(context)
        return try {
            first.openHelper.writableDatabase  // forces identity + integrity check
            first
        } catch (t: Throwable) {
            Log.w(TAG, "DB open failed, wiping and rebuilding: ${t.message}")
            runCatching { first.close() }
            runCatching { context.deleteDatabase(DB_NAME) }
            val rebuilt = buildDb(context)
            runCatching { rebuilt.openHelper.writableDatabase }
            rebuilt
        }
    }

    private fun buildDb(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideAlarmDao(db: AppDatabase): AlarmDao = db.alarmDao()

    @Provides
    fun provideWheelProfileDao(db: AppDatabase): com.eried.eucplanet.data.db.WheelProfileDao =
        db.wheelProfileDao()
}
