package com.eried.evendarkerbot.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile SettingsDao _settingsDao;

  private volatile TripDao _tripDao;

  private volatile AlarmDao _alarmDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(11) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `lastDeviceAddress` TEXT, `lastDeviceName` TEXT, `autoConnect` INTEGER NOT NULL, `tiltbackSpeedKmh` REAL NOT NULL, `alarmSpeedKmh` REAL NOT NULL, `safetyTiltbackKmh` REAL NOT NULL, `safetyAlarmKmh` REAL NOT NULL, `normalTiltbackKmh` REAL NOT NULL, `normalBeepKmh` REAL NOT NULL, `voiceEnabled` INTEGER NOT NULL, `voiceIntervalSeconds` INTEGER NOT NULL, `voiceSpeechRate` REAL NOT NULL, `voiceLocale` TEXT NOT NULL, `voiceReportSpeed` INTEGER NOT NULL, `voiceReportBattery` INTEGER NOT NULL, `voiceReportTemp` INTEGER NOT NULL, `voiceReportPwm` INTEGER NOT NULL, `voiceReportDistance` INTEGER NOT NULL, `triggerReportSpeed` INTEGER NOT NULL, `triggerReportBattery` INTEGER NOT NULL, `triggerReportTemp` INTEGER NOT NULL, `triggerReportPwm` INTEGER NOT NULL, `triggerReportDistance` INTEGER NOT NULL, `voiceReportRecording` INTEGER NOT NULL, `triggerReportRecording` INTEGER NOT NULL, `voiceReportOrder` TEXT NOT NULL, `announceWheelLock` INTEGER NOT NULL, `announceLights` INTEGER NOT NULL, `announceRecording` INTEGER NOT NULL, `announceConnection` INTEGER NOT NULL, `announceGps` INTEGER NOT NULL, `announceSafetyMode` INTEGER NOT NULL, `announceWelcome` INTEGER NOT NULL, `autoRecord` INTEGER NOT NULL, `flic1Address` TEXT, `flic1Name` TEXT NOT NULL, `flic1Click` TEXT NOT NULL, `flic1DoubleClick` TEXT NOT NULL, `flic1Hold` TEXT NOT NULL, `flic2Address` TEXT, `flic2Name` TEXT NOT NULL, `flic2Click` TEXT NOT NULL, `flic2DoubleClick` TEXT NOT NULL, `flic2Hold` TEXT NOT NULL, `autoLightsEnabled` INTEGER NOT NULL, `autoLightsOnMinutesBefore` INTEGER NOT NULL, `autoLightsOffMinutesAfter` INTEGER NOT NULL, `autoVolumeEnabled` INTEGER NOT NULL, `autoVolumeCurve` TEXT NOT NULL, `imperialUnits` INTEGER NOT NULL, `volumeKeysEnabled` INTEGER NOT NULL, `volumeUpClick` TEXT NOT NULL, `volumeUpHold` TEXT NOT NULL, `volumeDownClick` TEXT NOT NULL, `volumeDownHold` TEXT NOT NULL, `language` TEXT NOT NULL, `themeMode` TEXT NOT NULL, `accentColor` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `fileName` TEXT NOT NULL, `distanceKm` REAL NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `alarm_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `metric` TEXT NOT NULL, `comparator` TEXT NOT NULL, `threshold` REAL NOT NULL, `beepEnabled` INTEGER NOT NULL, `beepFrequency` INTEGER NOT NULL, `beepDurationMs` INTEGER NOT NULL, `beepCount` INTEGER NOT NULL, `voiceEnabled` INTEGER NOT NULL, `voiceText` TEXT NOT NULL, `vibrateEnabled` INTEGER NOT NULL, `vibrateDurationMs` INTEGER NOT NULL, `cooldownSeconds` INTEGER NOT NULL, `repeatWhileActive` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '1c0f7e53e4927829a1edc64e9f3bb752')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `app_settings`");
        db.execSQL("DROP TABLE IF EXISTS `trips`");
        db.execSQL("DROP TABLE IF EXISTS `alarm_rules`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsAppSettings = new HashMap<String, TableInfo.Column>(59);
        _columnsAppSettings.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("lastDeviceAddress", new TableInfo.Column("lastDeviceAddress", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("lastDeviceName", new TableInfo.Column("lastDeviceName", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoConnect", new TableInfo.Column("autoConnect", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("tiltbackSpeedKmh", new TableInfo.Column("tiltbackSpeedKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("alarmSpeedKmh", new TableInfo.Column("alarmSpeedKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("safetyTiltbackKmh", new TableInfo.Column("safetyTiltbackKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("safetyAlarmKmh", new TableInfo.Column("safetyAlarmKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("normalTiltbackKmh", new TableInfo.Column("normalTiltbackKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("normalBeepKmh", new TableInfo.Column("normalBeepKmh", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceEnabled", new TableInfo.Column("voiceEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceIntervalSeconds", new TableInfo.Column("voiceIntervalSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceSpeechRate", new TableInfo.Column("voiceSpeechRate", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceLocale", new TableInfo.Column("voiceLocale", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportSpeed", new TableInfo.Column("voiceReportSpeed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportBattery", new TableInfo.Column("voiceReportBattery", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportTemp", new TableInfo.Column("voiceReportTemp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportPwm", new TableInfo.Column("voiceReportPwm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportDistance", new TableInfo.Column("voiceReportDistance", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportSpeed", new TableInfo.Column("triggerReportSpeed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportBattery", new TableInfo.Column("triggerReportBattery", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportTemp", new TableInfo.Column("triggerReportTemp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportPwm", new TableInfo.Column("triggerReportPwm", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportDistance", new TableInfo.Column("triggerReportDistance", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportRecording", new TableInfo.Column("voiceReportRecording", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("triggerReportRecording", new TableInfo.Column("triggerReportRecording", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("voiceReportOrder", new TableInfo.Column("voiceReportOrder", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceWheelLock", new TableInfo.Column("announceWheelLock", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceLights", new TableInfo.Column("announceLights", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceRecording", new TableInfo.Column("announceRecording", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceConnection", new TableInfo.Column("announceConnection", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceGps", new TableInfo.Column("announceGps", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceSafetyMode", new TableInfo.Column("announceSafetyMode", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("announceWelcome", new TableInfo.Column("announceWelcome", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoRecord", new TableInfo.Column("autoRecord", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic1Address", new TableInfo.Column("flic1Address", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic1Name", new TableInfo.Column("flic1Name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic1Click", new TableInfo.Column("flic1Click", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic1DoubleClick", new TableInfo.Column("flic1DoubleClick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic1Hold", new TableInfo.Column("flic1Hold", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic2Address", new TableInfo.Column("flic2Address", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic2Name", new TableInfo.Column("flic2Name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic2Click", new TableInfo.Column("flic2Click", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic2DoubleClick", new TableInfo.Column("flic2DoubleClick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("flic2Hold", new TableInfo.Column("flic2Hold", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoLightsEnabled", new TableInfo.Column("autoLightsEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoLightsOnMinutesBefore", new TableInfo.Column("autoLightsOnMinutesBefore", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoLightsOffMinutesAfter", new TableInfo.Column("autoLightsOffMinutesAfter", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoVolumeEnabled", new TableInfo.Column("autoVolumeEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("autoVolumeCurve", new TableInfo.Column("autoVolumeCurve", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("imperialUnits", new TableInfo.Column("imperialUnits", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("volumeKeysEnabled", new TableInfo.Column("volumeKeysEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("volumeUpClick", new TableInfo.Column("volumeUpClick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("volumeUpHold", new TableInfo.Column("volumeUpHold", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("volumeDownClick", new TableInfo.Column("volumeDownClick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("volumeDownHold", new TableInfo.Column("volumeDownHold", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("language", new TableInfo.Column("language", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("themeMode", new TableInfo.Column("themeMode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppSettings.put("accentColor", new TableInfo.Column("accentColor", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysAppSettings = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesAppSettings = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoAppSettings = new TableInfo("app_settings", _columnsAppSettings, _foreignKeysAppSettings, _indicesAppSettings);
        final TableInfo _existingAppSettings = TableInfo.read(db, "app_settings");
        if (!_infoAppSettings.equals(_existingAppSettings)) {
          return new RoomOpenHelper.ValidationResult(false, "app_settings(com.eried.evendarkerbot.data.model.AppSettings).\n"
                  + " Expected:\n" + _infoAppSettings + "\n"
                  + " Found:\n" + _existingAppSettings);
        }
        final HashMap<String, TableInfo.Column> _columnsTrips = new HashMap<String, TableInfo.Column>(5);
        _columnsTrips.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTrips.put("startTime", new TableInfo.Column("startTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTrips.put("endTime", new TableInfo.Column("endTime", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTrips.put("fileName", new TableInfo.Column("fileName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsTrips.put("distanceKm", new TableInfo.Column("distanceKm", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysTrips = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesTrips = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoTrips = new TableInfo("trips", _columnsTrips, _foreignKeysTrips, _indicesTrips);
        final TableInfo _existingTrips = TableInfo.read(db, "trips");
        if (!_infoTrips.equals(_existingTrips)) {
          return new RoomOpenHelper.ValidationResult(false, "trips(com.eried.evendarkerbot.data.model.TripRecord).\n"
                  + " Expected:\n" + _infoTrips + "\n"
                  + " Found:\n" + _existingTrips);
        }
        final HashMap<String, TableInfo.Column> _columnsAlarmRules = new HashMap<String, TableInfo.Column>(17);
        _columnsAlarmRules.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("enabled", new TableInfo.Column("enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("sortOrder", new TableInfo.Column("sortOrder", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("metric", new TableInfo.Column("metric", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("comparator", new TableInfo.Column("comparator", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("threshold", new TableInfo.Column("threshold", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("beepEnabled", new TableInfo.Column("beepEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("beepFrequency", new TableInfo.Column("beepFrequency", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("beepDurationMs", new TableInfo.Column("beepDurationMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("beepCount", new TableInfo.Column("beepCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("voiceEnabled", new TableInfo.Column("voiceEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("voiceText", new TableInfo.Column("voiceText", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("vibrateEnabled", new TableInfo.Column("vibrateEnabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("vibrateDurationMs", new TableInfo.Column("vibrateDurationMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("cooldownSeconds", new TableInfo.Column("cooldownSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAlarmRules.put("repeatWhileActive", new TableInfo.Column("repeatWhileActive", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysAlarmRules = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesAlarmRules = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoAlarmRules = new TableInfo("alarm_rules", _columnsAlarmRules, _foreignKeysAlarmRules, _indicesAlarmRules);
        final TableInfo _existingAlarmRules = TableInfo.read(db, "alarm_rules");
        if (!_infoAlarmRules.equals(_existingAlarmRules)) {
          return new RoomOpenHelper.ValidationResult(false, "alarm_rules(com.eried.evendarkerbot.data.model.AlarmRule).\n"
                  + " Expected:\n" + _infoAlarmRules + "\n"
                  + " Found:\n" + _existingAlarmRules);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "1c0f7e53e4927829a1edc64e9f3bb752", "c7cf83341a38ca82296c35c63da4f817");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "app_settings","trips","alarm_rules");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `app_settings`");
      _db.execSQL("DELETE FROM `trips`");
      _db.execSQL("DELETE FROM `alarm_rules`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(SettingsDao.class, SettingsDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(TripDao.class, TripDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(AlarmDao.class, AlarmDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public SettingsDao settingsDao() {
    if (_settingsDao != null) {
      return _settingsDao;
    } else {
      synchronized(this) {
        if(_settingsDao == null) {
          _settingsDao = new SettingsDao_Impl(this);
        }
        return _settingsDao;
      }
    }
  }

  @Override
  public TripDao tripDao() {
    if (_tripDao != null) {
      return _tripDao;
    } else {
      synchronized(this) {
        if(_tripDao == null) {
          _tripDao = new TripDao_Impl(this);
        }
        return _tripDao;
      }
    }
  }

  @Override
  public AlarmDao alarmDao() {
    if (_alarmDao != null) {
      return _alarmDao;
    } else {
      synchronized(this) {
        if(_alarmDao == null) {
          _alarmDao = new AlarmDao_Impl(this);
        }
        return _alarmDao;
      }
    }
  }
}
