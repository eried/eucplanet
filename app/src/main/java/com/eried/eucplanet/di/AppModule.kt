package com.eried.eucplanet.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.db.AppDatabase
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.store.SettingsJson
import com.eried.eucplanet.data.store.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DB_NAME = "eucplanet.db"
    private const val TAG = "AppModule"

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore {
        val store = SettingsStore(context)
        copyLegacyRoomSettingsIfPresent(context, store)
        // After the legacy migration: if DataStore is still empty this is a
        // genuine fresh install, seed unit defaults from the device locale.
        // No-op for upgrading and existing users (their blob is already set).
        runBlocking { store.seedDefaultsIfAbsent() }
        return store
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return openOrRecover(context)
    }

    /**
     * If a pre-v45 database is on disk with a populated `app_settings` row,
     * serialise it into DataStore before Room runs its migrations. This makes
     * the upgrade non-destructive for the rider's toggle list. Idempotent , 
     * [SettingsStore.seedIfAbsent] only writes when DataStore is still empty.
     */
    private fun copyLegacyRoomSettingsIfPresent(context: Context, store: SettingsStore) {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return
        var sqlite: SQLiteDatabase? = null
        try {
            sqlite = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val cursor = sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='app_settings'",
                null
            )
            val tableExists = cursor.use { it.moveToFirst() }
            if (!tableExists) return
            val row = sqlite.rawQuery("SELECT * FROM app_settings WHERE id = 1", null)
            row.use { c ->
                if (!c.moveToFirst()) return
                val json = JSONObject().apply {
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        if (name == "id") continue
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> { /* skip */ }
                            android.database.Cursor.FIELD_TYPE_INTEGER -> put(name, c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> put(name, c.getDouble(i))
                            android.database.Cursor.FIELD_TYPE_STRING -> put(name, c.getString(i))
                            android.database.Cursor.FIELD_TYPE_BLOB -> { /* skip */ }
                        }
                    }
                }
                val imported = SettingsJson.fromJson(json)
                runBlocking { store.seedIfAbsent(imported) }
                Log.i(TAG, "Migrated legacy app_settings row into DataStore")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy settings migration skipped: ${t.message}")
        } finally {
            runCatching { sqlite?.close() }
        }
    }

    /**
     * v44 -> v45: drop the now-orphan `app_settings` table. Settings have
     * already been copied into DataStore by [copyLegacyRoomSettingsIfPresent].
     */
    private val MIGRATION_44_45 = object : Migration(44, 45) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS app_settings")
        }
    }

    /**
     * v45 -> v46: add the per-wheel `reverseSpeedDirection` flag for Begode
     * and Veteran wheels with inverted motor wiring.
     */
    private val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE wheel_profile ADD COLUMN reverseSpeedDirection INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v46 -> v47: add eucstats online-upload columns to the trips table.
     */
    private val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE trips ADD COLUMN tripUuid TEXT")
            db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsStatus INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsUploadedAt INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsValidation TEXT")
            db.execSQL("ALTER TABLE trips ADD COLUMN isMockLocation INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trips ADD COLUMN sampleCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trips ADD COLUMN wheelMetaJson TEXT")
        }
    }

    /**
     * v47 -> v48: add the per-alarm predictive lead time (ms). 0 keeps the
     * historic "fire on threshold crossing" behaviour for every existing rule.
     */
    private val MIGRATION_47_48 = object : Migration(47, 48) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE alarm_rules ADD COLUMN leadTimeMs INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Build the Room database with the v44->v45 migration. If the open still
     * fails (e.g. a future identity-hash mismatch from a forgotten migration),
     * wipe the DB file and rebuild, trip / alarm / profile loss is regrettable
     * but better than an unrecoverable crash on every cold start. Settings
     * stay safe in DataStore regardless.
     */
    private fun openOrRecover(context: Context): AppDatabase {
        val first = buildDb(context)
        return try {
            first.openHelper.writableDatabase
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
            .addMigrations(MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48)
            .build()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideAlarmDao(db: AppDatabase): AlarmDao = db.alarmDao()

    @Provides
    fun provideWheelProfileDao(db: AppDatabase): com.eried.eucplanet.data.db.WheelProfileDao =
        db.wheelProfileDao()
}
