package com.eried.evendarkerbot.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.eried.evendarkerbot.data.model.AppSettings;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class SettingsDao_Impl implements SettingsDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<AppSettings> __insertionAdapterOfAppSettings;

  private final EntityDeletionOrUpdateAdapter<AppSettings> __updateAdapterOfAppSettings;

  public SettingsDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfAppSettings = new EntityInsertionAdapter<AppSettings>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `app_settings` (`id`,`lastDeviceAddress`,`lastDeviceName`,`autoConnect`,`tiltbackSpeedKmh`,`alarmSpeedKmh`,`safetyTiltbackKmh`,`safetyAlarmKmh`,`normalTiltbackKmh`,`normalBeepKmh`,`voiceEnabled`,`voiceIntervalSeconds`,`voiceSpeechRate`,`voiceLocale`,`voiceReportSpeed`,`voiceReportBattery`,`voiceReportTemp`,`voiceReportPwm`,`voiceReportDistance`,`triggerReportSpeed`,`triggerReportBattery`,`triggerReportTemp`,`triggerReportPwm`,`triggerReportDistance`,`voiceReportRecording`,`triggerReportRecording`,`voiceReportOrder`,`announceWheelLock`,`announceLights`,`announceRecording`,`announceConnection`,`announceGps`,`announceSafetyMode`,`autoRecord`,`flic1Address`,`flic1Name`,`flic1Click`,`flic1DoubleClick`,`flic1Hold`,`flic2Address`,`flic2Name`,`flic2Click`,`flic2DoubleClick`,`flic2Hold`,`autoLightsEnabled`,`autoLightsOnMinutesBefore`,`autoLightsOffMinutesAfter`,`autoVolumeEnabled`,`autoVolumeCurve`,`imperialUnits`,`volumeKeysEnabled`,`volumeUpClick`,`volumeUpHold`,`volumeDownClick`,`volumeDownHold`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AppSettings entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getLastDeviceAddress() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getLastDeviceAddress());
        }
        if (entity.getLastDeviceName() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getLastDeviceName());
        }
        final int _tmp = entity.getAutoConnect() ? 1 : 0;
        statement.bindLong(4, _tmp);
        statement.bindDouble(5, entity.getTiltbackSpeedKmh());
        statement.bindDouble(6, entity.getAlarmSpeedKmh());
        statement.bindDouble(7, entity.getSafetyTiltbackKmh());
        statement.bindDouble(8, entity.getSafetyAlarmKmh());
        statement.bindDouble(9, entity.getNormalTiltbackKmh());
        statement.bindDouble(10, entity.getNormalBeepKmh());
        final int _tmp_1 = entity.getVoiceEnabled() ? 1 : 0;
        statement.bindLong(11, _tmp_1);
        statement.bindLong(12, entity.getVoiceIntervalSeconds());
        statement.bindDouble(13, entity.getVoiceSpeechRate());
        statement.bindString(14, entity.getVoiceLocale());
        final int _tmp_2 = entity.getVoiceReportSpeed() ? 1 : 0;
        statement.bindLong(15, _tmp_2);
        final int _tmp_3 = entity.getVoiceReportBattery() ? 1 : 0;
        statement.bindLong(16, _tmp_3);
        final int _tmp_4 = entity.getVoiceReportTemp() ? 1 : 0;
        statement.bindLong(17, _tmp_4);
        final int _tmp_5 = entity.getVoiceReportPwm() ? 1 : 0;
        statement.bindLong(18, _tmp_5);
        final int _tmp_6 = entity.getVoiceReportDistance() ? 1 : 0;
        statement.bindLong(19, _tmp_6);
        final int _tmp_7 = entity.getTriggerReportSpeed() ? 1 : 0;
        statement.bindLong(20, _tmp_7);
        final int _tmp_8 = entity.getTriggerReportBattery() ? 1 : 0;
        statement.bindLong(21, _tmp_8);
        final int _tmp_9 = entity.getTriggerReportTemp() ? 1 : 0;
        statement.bindLong(22, _tmp_9);
        final int _tmp_10 = entity.getTriggerReportPwm() ? 1 : 0;
        statement.bindLong(23, _tmp_10);
        final int _tmp_11 = entity.getTriggerReportDistance() ? 1 : 0;
        statement.bindLong(24, _tmp_11);
        final int _tmp_12 = entity.getVoiceReportRecording() ? 1 : 0;
        statement.bindLong(25, _tmp_12);
        final int _tmp_13 = entity.getTriggerReportRecording() ? 1 : 0;
        statement.bindLong(26, _tmp_13);
        statement.bindString(27, entity.getVoiceReportOrder());
        final int _tmp_14 = entity.getAnnounceWheelLock() ? 1 : 0;
        statement.bindLong(28, _tmp_14);
        final int _tmp_15 = entity.getAnnounceLights() ? 1 : 0;
        statement.bindLong(29, _tmp_15);
        final int _tmp_16 = entity.getAnnounceRecording() ? 1 : 0;
        statement.bindLong(30, _tmp_16);
        final int _tmp_17 = entity.getAnnounceConnection() ? 1 : 0;
        statement.bindLong(31, _tmp_17);
        final int _tmp_18 = entity.getAnnounceGps() ? 1 : 0;
        statement.bindLong(32, _tmp_18);
        final int _tmp_19 = entity.getAnnounceSafetyMode() ? 1 : 0;
        statement.bindLong(33, _tmp_19);
        final int _tmp_20 = entity.getAutoRecord() ? 1 : 0;
        statement.bindLong(34, _tmp_20);
        if (entity.getFlic1Address() == null) {
          statement.bindNull(35);
        } else {
          statement.bindString(35, entity.getFlic1Address());
        }
        statement.bindString(36, entity.getFlic1Name());
        statement.bindString(37, entity.getFlic1Click());
        statement.bindString(38, entity.getFlic1DoubleClick());
        statement.bindString(39, entity.getFlic1Hold());
        if (entity.getFlic2Address() == null) {
          statement.bindNull(40);
        } else {
          statement.bindString(40, entity.getFlic2Address());
        }
        statement.bindString(41, entity.getFlic2Name());
        statement.bindString(42, entity.getFlic2Click());
        statement.bindString(43, entity.getFlic2DoubleClick());
        statement.bindString(44, entity.getFlic2Hold());
        final int _tmp_21 = entity.getAutoLightsEnabled() ? 1 : 0;
        statement.bindLong(45, _tmp_21);
        statement.bindLong(46, entity.getAutoLightsOnMinutesBefore());
        statement.bindLong(47, entity.getAutoLightsOffMinutesAfter());
        final int _tmp_22 = entity.getAutoVolumeEnabled() ? 1 : 0;
        statement.bindLong(48, _tmp_22);
        statement.bindString(49, entity.getAutoVolumeCurve());
        final int _tmp_23 = entity.getImperialUnits() ? 1 : 0;
        statement.bindLong(50, _tmp_23);
        final int _tmp_24 = entity.getVolumeKeysEnabled() ? 1 : 0;
        statement.bindLong(51, _tmp_24);
        statement.bindString(52, entity.getVolumeUpClick());
        statement.bindString(53, entity.getVolumeUpHold());
        statement.bindString(54, entity.getVolumeDownClick());
        statement.bindString(55, entity.getVolumeDownHold());
      }
    };
    this.__updateAdapterOfAppSettings = new EntityDeletionOrUpdateAdapter<AppSettings>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `app_settings` SET `id` = ?,`lastDeviceAddress` = ?,`lastDeviceName` = ?,`autoConnect` = ?,`tiltbackSpeedKmh` = ?,`alarmSpeedKmh` = ?,`safetyTiltbackKmh` = ?,`safetyAlarmKmh` = ?,`normalTiltbackKmh` = ?,`normalBeepKmh` = ?,`voiceEnabled` = ?,`voiceIntervalSeconds` = ?,`voiceSpeechRate` = ?,`voiceLocale` = ?,`voiceReportSpeed` = ?,`voiceReportBattery` = ?,`voiceReportTemp` = ?,`voiceReportPwm` = ?,`voiceReportDistance` = ?,`triggerReportSpeed` = ?,`triggerReportBattery` = ?,`triggerReportTemp` = ?,`triggerReportPwm` = ?,`triggerReportDistance` = ?,`voiceReportRecording` = ?,`triggerReportRecording` = ?,`voiceReportOrder` = ?,`announceWheelLock` = ?,`announceLights` = ?,`announceRecording` = ?,`announceConnection` = ?,`announceGps` = ?,`announceSafetyMode` = ?,`autoRecord` = ?,`flic1Address` = ?,`flic1Name` = ?,`flic1Click` = ?,`flic1DoubleClick` = ?,`flic1Hold` = ?,`flic2Address` = ?,`flic2Name` = ?,`flic2Click` = ?,`flic2DoubleClick` = ?,`flic2Hold` = ?,`autoLightsEnabled` = ?,`autoLightsOnMinutesBefore` = ?,`autoLightsOffMinutesAfter` = ?,`autoVolumeEnabled` = ?,`autoVolumeCurve` = ?,`imperialUnits` = ?,`volumeKeysEnabled` = ?,`volumeUpClick` = ?,`volumeUpHold` = ?,`volumeDownClick` = ?,`volumeDownHold` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AppSettings entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getLastDeviceAddress() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getLastDeviceAddress());
        }
        if (entity.getLastDeviceName() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getLastDeviceName());
        }
        final int _tmp = entity.getAutoConnect() ? 1 : 0;
        statement.bindLong(4, _tmp);
        statement.bindDouble(5, entity.getTiltbackSpeedKmh());
        statement.bindDouble(6, entity.getAlarmSpeedKmh());
        statement.bindDouble(7, entity.getSafetyTiltbackKmh());
        statement.bindDouble(8, entity.getSafetyAlarmKmh());
        statement.bindDouble(9, entity.getNormalTiltbackKmh());
        statement.bindDouble(10, entity.getNormalBeepKmh());
        final int _tmp_1 = entity.getVoiceEnabled() ? 1 : 0;
        statement.bindLong(11, _tmp_1);
        statement.bindLong(12, entity.getVoiceIntervalSeconds());
        statement.bindDouble(13, entity.getVoiceSpeechRate());
        statement.bindString(14, entity.getVoiceLocale());
        final int _tmp_2 = entity.getVoiceReportSpeed() ? 1 : 0;
        statement.bindLong(15, _tmp_2);
        final int _tmp_3 = entity.getVoiceReportBattery() ? 1 : 0;
        statement.bindLong(16, _tmp_3);
        final int _tmp_4 = entity.getVoiceReportTemp() ? 1 : 0;
        statement.bindLong(17, _tmp_4);
        final int _tmp_5 = entity.getVoiceReportPwm() ? 1 : 0;
        statement.bindLong(18, _tmp_5);
        final int _tmp_6 = entity.getVoiceReportDistance() ? 1 : 0;
        statement.bindLong(19, _tmp_6);
        final int _tmp_7 = entity.getTriggerReportSpeed() ? 1 : 0;
        statement.bindLong(20, _tmp_7);
        final int _tmp_8 = entity.getTriggerReportBattery() ? 1 : 0;
        statement.bindLong(21, _tmp_8);
        final int _tmp_9 = entity.getTriggerReportTemp() ? 1 : 0;
        statement.bindLong(22, _tmp_9);
        final int _tmp_10 = entity.getTriggerReportPwm() ? 1 : 0;
        statement.bindLong(23, _tmp_10);
        final int _tmp_11 = entity.getTriggerReportDistance() ? 1 : 0;
        statement.bindLong(24, _tmp_11);
        final int _tmp_12 = entity.getVoiceReportRecording() ? 1 : 0;
        statement.bindLong(25, _tmp_12);
        final int _tmp_13 = entity.getTriggerReportRecording() ? 1 : 0;
        statement.bindLong(26, _tmp_13);
        statement.bindString(27, entity.getVoiceReportOrder());
        final int _tmp_14 = entity.getAnnounceWheelLock() ? 1 : 0;
        statement.bindLong(28, _tmp_14);
        final int _tmp_15 = entity.getAnnounceLights() ? 1 : 0;
        statement.bindLong(29, _tmp_15);
        final int _tmp_16 = entity.getAnnounceRecording() ? 1 : 0;
        statement.bindLong(30, _tmp_16);
        final int _tmp_17 = entity.getAnnounceConnection() ? 1 : 0;
        statement.bindLong(31, _tmp_17);
        final int _tmp_18 = entity.getAnnounceGps() ? 1 : 0;
        statement.bindLong(32, _tmp_18);
        final int _tmp_19 = entity.getAnnounceSafetyMode() ? 1 : 0;
        statement.bindLong(33, _tmp_19);
        final int _tmp_20 = entity.getAutoRecord() ? 1 : 0;
        statement.bindLong(34, _tmp_20);
        if (entity.getFlic1Address() == null) {
          statement.bindNull(35);
        } else {
          statement.bindString(35, entity.getFlic1Address());
        }
        statement.bindString(36, entity.getFlic1Name());
        statement.bindString(37, entity.getFlic1Click());
        statement.bindString(38, entity.getFlic1DoubleClick());
        statement.bindString(39, entity.getFlic1Hold());
        if (entity.getFlic2Address() == null) {
          statement.bindNull(40);
        } else {
          statement.bindString(40, entity.getFlic2Address());
        }
        statement.bindString(41, entity.getFlic2Name());
        statement.bindString(42, entity.getFlic2Click());
        statement.bindString(43, entity.getFlic2DoubleClick());
        statement.bindString(44, entity.getFlic2Hold());
        final int _tmp_21 = entity.getAutoLightsEnabled() ? 1 : 0;
        statement.bindLong(45, _tmp_21);
        statement.bindLong(46, entity.getAutoLightsOnMinutesBefore());
        statement.bindLong(47, entity.getAutoLightsOffMinutesAfter());
        final int _tmp_22 = entity.getAutoVolumeEnabled() ? 1 : 0;
        statement.bindLong(48, _tmp_22);
        statement.bindString(49, entity.getAutoVolumeCurve());
        final int _tmp_23 = entity.getImperialUnits() ? 1 : 0;
        statement.bindLong(50, _tmp_23);
        final int _tmp_24 = entity.getVolumeKeysEnabled() ? 1 : 0;
        statement.bindLong(51, _tmp_24);
        statement.bindString(52, entity.getVolumeUpClick());
        statement.bindString(53, entity.getVolumeUpHold());
        statement.bindString(54, entity.getVolumeDownClick());
        statement.bindString(55, entity.getVolumeDownHold());
        statement.bindLong(56, entity.getId());
      }
    };
  }

  @Override
  public Object upsert(final AppSettings settings, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfAppSettings.insert(settings);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final AppSettings settings, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfAppSettings.handle(settings);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<AppSettings> observe() {
    final String _sql = "SELECT * FROM app_settings WHERE id = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"app_settings"}, new Callable<AppSettings>() {
      @Override
      @Nullable
      public AppSettings call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfLastDeviceAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "lastDeviceAddress");
          final int _cursorIndexOfLastDeviceName = CursorUtil.getColumnIndexOrThrow(_cursor, "lastDeviceName");
          final int _cursorIndexOfAutoConnect = CursorUtil.getColumnIndexOrThrow(_cursor, "autoConnect");
          final int _cursorIndexOfTiltbackSpeedKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "tiltbackSpeedKmh");
          final int _cursorIndexOfAlarmSpeedKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "alarmSpeedKmh");
          final int _cursorIndexOfSafetyTiltbackKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "safetyTiltbackKmh");
          final int _cursorIndexOfSafetyAlarmKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "safetyAlarmKmh");
          final int _cursorIndexOfNormalTiltbackKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "normalTiltbackKmh");
          final int _cursorIndexOfNormalBeepKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "normalBeepKmh");
          final int _cursorIndexOfVoiceEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceEnabled");
          final int _cursorIndexOfVoiceIntervalSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceIntervalSeconds");
          final int _cursorIndexOfVoiceSpeechRate = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceSpeechRate");
          final int _cursorIndexOfVoiceLocale = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceLocale");
          final int _cursorIndexOfVoiceReportSpeed = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportSpeed");
          final int _cursorIndexOfVoiceReportBattery = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportBattery");
          final int _cursorIndexOfVoiceReportTemp = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportTemp");
          final int _cursorIndexOfVoiceReportPwm = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportPwm");
          final int _cursorIndexOfVoiceReportDistance = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportDistance");
          final int _cursorIndexOfTriggerReportSpeed = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportSpeed");
          final int _cursorIndexOfTriggerReportBattery = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportBattery");
          final int _cursorIndexOfTriggerReportTemp = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportTemp");
          final int _cursorIndexOfTriggerReportPwm = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportPwm");
          final int _cursorIndexOfTriggerReportDistance = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportDistance");
          final int _cursorIndexOfVoiceReportRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportRecording");
          final int _cursorIndexOfTriggerReportRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportRecording");
          final int _cursorIndexOfVoiceReportOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportOrder");
          final int _cursorIndexOfAnnounceWheelLock = CursorUtil.getColumnIndexOrThrow(_cursor, "announceWheelLock");
          final int _cursorIndexOfAnnounceLights = CursorUtil.getColumnIndexOrThrow(_cursor, "announceLights");
          final int _cursorIndexOfAnnounceRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "announceRecording");
          final int _cursorIndexOfAnnounceConnection = CursorUtil.getColumnIndexOrThrow(_cursor, "announceConnection");
          final int _cursorIndexOfAnnounceGps = CursorUtil.getColumnIndexOrThrow(_cursor, "announceGps");
          final int _cursorIndexOfAnnounceSafetyMode = CursorUtil.getColumnIndexOrThrow(_cursor, "announceSafetyMode");
          final int _cursorIndexOfAutoRecord = CursorUtil.getColumnIndexOrThrow(_cursor, "autoRecord");
          final int _cursorIndexOfFlic1Address = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Address");
          final int _cursorIndexOfFlic1Name = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Name");
          final int _cursorIndexOfFlic1Click = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Click");
          final int _cursorIndexOfFlic1DoubleClick = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1DoubleClick");
          final int _cursorIndexOfFlic1Hold = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Hold");
          final int _cursorIndexOfFlic2Address = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Address");
          final int _cursorIndexOfFlic2Name = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Name");
          final int _cursorIndexOfFlic2Click = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Click");
          final int _cursorIndexOfFlic2DoubleClick = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2DoubleClick");
          final int _cursorIndexOfFlic2Hold = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Hold");
          final int _cursorIndexOfAutoLightsEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsEnabled");
          final int _cursorIndexOfAutoLightsOnMinutesBefore = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsOnMinutesBefore");
          final int _cursorIndexOfAutoLightsOffMinutesAfter = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsOffMinutesAfter");
          final int _cursorIndexOfAutoVolumeEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "autoVolumeEnabled");
          final int _cursorIndexOfAutoVolumeCurve = CursorUtil.getColumnIndexOrThrow(_cursor, "autoVolumeCurve");
          final int _cursorIndexOfImperialUnits = CursorUtil.getColumnIndexOrThrow(_cursor, "imperialUnits");
          final int _cursorIndexOfVolumeKeysEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeKeysEnabled");
          final int _cursorIndexOfVolumeUpClick = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeUpClick");
          final int _cursorIndexOfVolumeUpHold = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeUpHold");
          final int _cursorIndexOfVolumeDownClick = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeDownClick");
          final int _cursorIndexOfVolumeDownHold = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeDownHold");
          final AppSettings _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpLastDeviceAddress;
            if (_cursor.isNull(_cursorIndexOfLastDeviceAddress)) {
              _tmpLastDeviceAddress = null;
            } else {
              _tmpLastDeviceAddress = _cursor.getString(_cursorIndexOfLastDeviceAddress);
            }
            final String _tmpLastDeviceName;
            if (_cursor.isNull(_cursorIndexOfLastDeviceName)) {
              _tmpLastDeviceName = null;
            } else {
              _tmpLastDeviceName = _cursor.getString(_cursorIndexOfLastDeviceName);
            }
            final boolean _tmpAutoConnect;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfAutoConnect);
            _tmpAutoConnect = _tmp != 0;
            final float _tmpTiltbackSpeedKmh;
            _tmpTiltbackSpeedKmh = _cursor.getFloat(_cursorIndexOfTiltbackSpeedKmh);
            final float _tmpAlarmSpeedKmh;
            _tmpAlarmSpeedKmh = _cursor.getFloat(_cursorIndexOfAlarmSpeedKmh);
            final float _tmpSafetyTiltbackKmh;
            _tmpSafetyTiltbackKmh = _cursor.getFloat(_cursorIndexOfSafetyTiltbackKmh);
            final float _tmpSafetyAlarmKmh;
            _tmpSafetyAlarmKmh = _cursor.getFloat(_cursorIndexOfSafetyAlarmKmh);
            final float _tmpNormalTiltbackKmh;
            _tmpNormalTiltbackKmh = _cursor.getFloat(_cursorIndexOfNormalTiltbackKmh);
            final float _tmpNormalBeepKmh;
            _tmpNormalBeepKmh = _cursor.getFloat(_cursorIndexOfNormalBeepKmh);
            final boolean _tmpVoiceEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfVoiceEnabled);
            _tmpVoiceEnabled = _tmp_1 != 0;
            final int _tmpVoiceIntervalSeconds;
            _tmpVoiceIntervalSeconds = _cursor.getInt(_cursorIndexOfVoiceIntervalSeconds);
            final float _tmpVoiceSpeechRate;
            _tmpVoiceSpeechRate = _cursor.getFloat(_cursorIndexOfVoiceSpeechRate);
            final String _tmpVoiceLocale;
            _tmpVoiceLocale = _cursor.getString(_cursorIndexOfVoiceLocale);
            final boolean _tmpVoiceReportSpeed;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfVoiceReportSpeed);
            _tmpVoiceReportSpeed = _tmp_2 != 0;
            final boolean _tmpVoiceReportBattery;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfVoiceReportBattery);
            _tmpVoiceReportBattery = _tmp_3 != 0;
            final boolean _tmpVoiceReportTemp;
            final int _tmp_4;
            _tmp_4 = _cursor.getInt(_cursorIndexOfVoiceReportTemp);
            _tmpVoiceReportTemp = _tmp_4 != 0;
            final boolean _tmpVoiceReportPwm;
            final int _tmp_5;
            _tmp_5 = _cursor.getInt(_cursorIndexOfVoiceReportPwm);
            _tmpVoiceReportPwm = _tmp_5 != 0;
            final boolean _tmpVoiceReportDistance;
            final int _tmp_6;
            _tmp_6 = _cursor.getInt(_cursorIndexOfVoiceReportDistance);
            _tmpVoiceReportDistance = _tmp_6 != 0;
            final boolean _tmpTriggerReportSpeed;
            final int _tmp_7;
            _tmp_7 = _cursor.getInt(_cursorIndexOfTriggerReportSpeed);
            _tmpTriggerReportSpeed = _tmp_7 != 0;
            final boolean _tmpTriggerReportBattery;
            final int _tmp_8;
            _tmp_8 = _cursor.getInt(_cursorIndexOfTriggerReportBattery);
            _tmpTriggerReportBattery = _tmp_8 != 0;
            final boolean _tmpTriggerReportTemp;
            final int _tmp_9;
            _tmp_9 = _cursor.getInt(_cursorIndexOfTriggerReportTemp);
            _tmpTriggerReportTemp = _tmp_9 != 0;
            final boolean _tmpTriggerReportPwm;
            final int _tmp_10;
            _tmp_10 = _cursor.getInt(_cursorIndexOfTriggerReportPwm);
            _tmpTriggerReportPwm = _tmp_10 != 0;
            final boolean _tmpTriggerReportDistance;
            final int _tmp_11;
            _tmp_11 = _cursor.getInt(_cursorIndexOfTriggerReportDistance);
            _tmpTriggerReportDistance = _tmp_11 != 0;
            final boolean _tmpVoiceReportRecording;
            final int _tmp_12;
            _tmp_12 = _cursor.getInt(_cursorIndexOfVoiceReportRecording);
            _tmpVoiceReportRecording = _tmp_12 != 0;
            final boolean _tmpTriggerReportRecording;
            final int _tmp_13;
            _tmp_13 = _cursor.getInt(_cursorIndexOfTriggerReportRecording);
            _tmpTriggerReportRecording = _tmp_13 != 0;
            final String _tmpVoiceReportOrder;
            _tmpVoiceReportOrder = _cursor.getString(_cursorIndexOfVoiceReportOrder);
            final boolean _tmpAnnounceWheelLock;
            final int _tmp_14;
            _tmp_14 = _cursor.getInt(_cursorIndexOfAnnounceWheelLock);
            _tmpAnnounceWheelLock = _tmp_14 != 0;
            final boolean _tmpAnnounceLights;
            final int _tmp_15;
            _tmp_15 = _cursor.getInt(_cursorIndexOfAnnounceLights);
            _tmpAnnounceLights = _tmp_15 != 0;
            final boolean _tmpAnnounceRecording;
            final int _tmp_16;
            _tmp_16 = _cursor.getInt(_cursorIndexOfAnnounceRecording);
            _tmpAnnounceRecording = _tmp_16 != 0;
            final boolean _tmpAnnounceConnection;
            final int _tmp_17;
            _tmp_17 = _cursor.getInt(_cursorIndexOfAnnounceConnection);
            _tmpAnnounceConnection = _tmp_17 != 0;
            final boolean _tmpAnnounceGps;
            final int _tmp_18;
            _tmp_18 = _cursor.getInt(_cursorIndexOfAnnounceGps);
            _tmpAnnounceGps = _tmp_18 != 0;
            final boolean _tmpAnnounceSafetyMode;
            final int _tmp_19;
            _tmp_19 = _cursor.getInt(_cursorIndexOfAnnounceSafetyMode);
            _tmpAnnounceSafetyMode = _tmp_19 != 0;
            final boolean _tmpAutoRecord;
            final int _tmp_20;
            _tmp_20 = _cursor.getInt(_cursorIndexOfAutoRecord);
            _tmpAutoRecord = _tmp_20 != 0;
            final String _tmpFlic1Address;
            if (_cursor.isNull(_cursorIndexOfFlic1Address)) {
              _tmpFlic1Address = null;
            } else {
              _tmpFlic1Address = _cursor.getString(_cursorIndexOfFlic1Address);
            }
            final String _tmpFlic1Name;
            _tmpFlic1Name = _cursor.getString(_cursorIndexOfFlic1Name);
            final String _tmpFlic1Click;
            _tmpFlic1Click = _cursor.getString(_cursorIndexOfFlic1Click);
            final String _tmpFlic1DoubleClick;
            _tmpFlic1DoubleClick = _cursor.getString(_cursorIndexOfFlic1DoubleClick);
            final String _tmpFlic1Hold;
            _tmpFlic1Hold = _cursor.getString(_cursorIndexOfFlic1Hold);
            final String _tmpFlic2Address;
            if (_cursor.isNull(_cursorIndexOfFlic2Address)) {
              _tmpFlic2Address = null;
            } else {
              _tmpFlic2Address = _cursor.getString(_cursorIndexOfFlic2Address);
            }
            final String _tmpFlic2Name;
            _tmpFlic2Name = _cursor.getString(_cursorIndexOfFlic2Name);
            final String _tmpFlic2Click;
            _tmpFlic2Click = _cursor.getString(_cursorIndexOfFlic2Click);
            final String _tmpFlic2DoubleClick;
            _tmpFlic2DoubleClick = _cursor.getString(_cursorIndexOfFlic2DoubleClick);
            final String _tmpFlic2Hold;
            _tmpFlic2Hold = _cursor.getString(_cursorIndexOfFlic2Hold);
            final boolean _tmpAutoLightsEnabled;
            final int _tmp_21;
            _tmp_21 = _cursor.getInt(_cursorIndexOfAutoLightsEnabled);
            _tmpAutoLightsEnabled = _tmp_21 != 0;
            final int _tmpAutoLightsOnMinutesBefore;
            _tmpAutoLightsOnMinutesBefore = _cursor.getInt(_cursorIndexOfAutoLightsOnMinutesBefore);
            final int _tmpAutoLightsOffMinutesAfter;
            _tmpAutoLightsOffMinutesAfter = _cursor.getInt(_cursorIndexOfAutoLightsOffMinutesAfter);
            final boolean _tmpAutoVolumeEnabled;
            final int _tmp_22;
            _tmp_22 = _cursor.getInt(_cursorIndexOfAutoVolumeEnabled);
            _tmpAutoVolumeEnabled = _tmp_22 != 0;
            final String _tmpAutoVolumeCurve;
            _tmpAutoVolumeCurve = _cursor.getString(_cursorIndexOfAutoVolumeCurve);
            final boolean _tmpImperialUnits;
            final int _tmp_23;
            _tmp_23 = _cursor.getInt(_cursorIndexOfImperialUnits);
            _tmpImperialUnits = _tmp_23 != 0;
            final boolean _tmpVolumeKeysEnabled;
            final int _tmp_24;
            _tmp_24 = _cursor.getInt(_cursorIndexOfVolumeKeysEnabled);
            _tmpVolumeKeysEnabled = _tmp_24 != 0;
            final String _tmpVolumeUpClick;
            _tmpVolumeUpClick = _cursor.getString(_cursorIndexOfVolumeUpClick);
            final String _tmpVolumeUpHold;
            _tmpVolumeUpHold = _cursor.getString(_cursorIndexOfVolumeUpHold);
            final String _tmpVolumeDownClick;
            _tmpVolumeDownClick = _cursor.getString(_cursorIndexOfVolumeDownClick);
            final String _tmpVolumeDownHold;
            _tmpVolumeDownHold = _cursor.getString(_cursorIndexOfVolumeDownHold);
            _result = new AppSettings(_tmpId,_tmpLastDeviceAddress,_tmpLastDeviceName,_tmpAutoConnect,_tmpTiltbackSpeedKmh,_tmpAlarmSpeedKmh,_tmpSafetyTiltbackKmh,_tmpSafetyAlarmKmh,_tmpNormalTiltbackKmh,_tmpNormalBeepKmh,_tmpVoiceEnabled,_tmpVoiceIntervalSeconds,_tmpVoiceSpeechRate,_tmpVoiceLocale,_tmpVoiceReportSpeed,_tmpVoiceReportBattery,_tmpVoiceReportTemp,_tmpVoiceReportPwm,_tmpVoiceReportDistance,_tmpTriggerReportSpeed,_tmpTriggerReportBattery,_tmpTriggerReportTemp,_tmpTriggerReportPwm,_tmpTriggerReportDistance,_tmpVoiceReportRecording,_tmpTriggerReportRecording,_tmpVoiceReportOrder,_tmpAnnounceWheelLock,_tmpAnnounceLights,_tmpAnnounceRecording,_tmpAnnounceConnection,_tmpAnnounceGps,_tmpAnnounceSafetyMode,_tmpAutoRecord,_tmpFlic1Address,_tmpFlic1Name,_tmpFlic1Click,_tmpFlic1DoubleClick,_tmpFlic1Hold,_tmpFlic2Address,_tmpFlic2Name,_tmpFlic2Click,_tmpFlic2DoubleClick,_tmpFlic2Hold,_tmpAutoLightsEnabled,_tmpAutoLightsOnMinutesBefore,_tmpAutoLightsOffMinutesAfter,_tmpAutoVolumeEnabled,_tmpAutoVolumeCurve,_tmpImperialUnits,_tmpVolumeKeysEnabled,_tmpVolumeUpClick,_tmpVolumeUpHold,_tmpVolumeDownClick,_tmpVolumeDownHold);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object get(final Continuation<? super AppSettings> $completion) {
    final String _sql = "SELECT * FROM app_settings WHERE id = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<AppSettings>() {
      @Override
      @Nullable
      public AppSettings call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfLastDeviceAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "lastDeviceAddress");
          final int _cursorIndexOfLastDeviceName = CursorUtil.getColumnIndexOrThrow(_cursor, "lastDeviceName");
          final int _cursorIndexOfAutoConnect = CursorUtil.getColumnIndexOrThrow(_cursor, "autoConnect");
          final int _cursorIndexOfTiltbackSpeedKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "tiltbackSpeedKmh");
          final int _cursorIndexOfAlarmSpeedKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "alarmSpeedKmh");
          final int _cursorIndexOfSafetyTiltbackKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "safetyTiltbackKmh");
          final int _cursorIndexOfSafetyAlarmKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "safetyAlarmKmh");
          final int _cursorIndexOfNormalTiltbackKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "normalTiltbackKmh");
          final int _cursorIndexOfNormalBeepKmh = CursorUtil.getColumnIndexOrThrow(_cursor, "normalBeepKmh");
          final int _cursorIndexOfVoiceEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceEnabled");
          final int _cursorIndexOfVoiceIntervalSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceIntervalSeconds");
          final int _cursorIndexOfVoiceSpeechRate = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceSpeechRate");
          final int _cursorIndexOfVoiceLocale = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceLocale");
          final int _cursorIndexOfVoiceReportSpeed = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportSpeed");
          final int _cursorIndexOfVoiceReportBattery = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportBattery");
          final int _cursorIndexOfVoiceReportTemp = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportTemp");
          final int _cursorIndexOfVoiceReportPwm = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportPwm");
          final int _cursorIndexOfVoiceReportDistance = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportDistance");
          final int _cursorIndexOfTriggerReportSpeed = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportSpeed");
          final int _cursorIndexOfTriggerReportBattery = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportBattery");
          final int _cursorIndexOfTriggerReportTemp = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportTemp");
          final int _cursorIndexOfTriggerReportPwm = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportPwm");
          final int _cursorIndexOfTriggerReportDistance = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportDistance");
          final int _cursorIndexOfVoiceReportRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportRecording");
          final int _cursorIndexOfTriggerReportRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "triggerReportRecording");
          final int _cursorIndexOfVoiceReportOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceReportOrder");
          final int _cursorIndexOfAnnounceWheelLock = CursorUtil.getColumnIndexOrThrow(_cursor, "announceWheelLock");
          final int _cursorIndexOfAnnounceLights = CursorUtil.getColumnIndexOrThrow(_cursor, "announceLights");
          final int _cursorIndexOfAnnounceRecording = CursorUtil.getColumnIndexOrThrow(_cursor, "announceRecording");
          final int _cursorIndexOfAnnounceConnection = CursorUtil.getColumnIndexOrThrow(_cursor, "announceConnection");
          final int _cursorIndexOfAnnounceGps = CursorUtil.getColumnIndexOrThrow(_cursor, "announceGps");
          final int _cursorIndexOfAnnounceSafetyMode = CursorUtil.getColumnIndexOrThrow(_cursor, "announceSafetyMode");
          final int _cursorIndexOfAutoRecord = CursorUtil.getColumnIndexOrThrow(_cursor, "autoRecord");
          final int _cursorIndexOfFlic1Address = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Address");
          final int _cursorIndexOfFlic1Name = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Name");
          final int _cursorIndexOfFlic1Click = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Click");
          final int _cursorIndexOfFlic1DoubleClick = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1DoubleClick");
          final int _cursorIndexOfFlic1Hold = CursorUtil.getColumnIndexOrThrow(_cursor, "flic1Hold");
          final int _cursorIndexOfFlic2Address = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Address");
          final int _cursorIndexOfFlic2Name = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Name");
          final int _cursorIndexOfFlic2Click = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Click");
          final int _cursorIndexOfFlic2DoubleClick = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2DoubleClick");
          final int _cursorIndexOfFlic2Hold = CursorUtil.getColumnIndexOrThrow(_cursor, "flic2Hold");
          final int _cursorIndexOfAutoLightsEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsEnabled");
          final int _cursorIndexOfAutoLightsOnMinutesBefore = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsOnMinutesBefore");
          final int _cursorIndexOfAutoLightsOffMinutesAfter = CursorUtil.getColumnIndexOrThrow(_cursor, "autoLightsOffMinutesAfter");
          final int _cursorIndexOfAutoVolumeEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "autoVolumeEnabled");
          final int _cursorIndexOfAutoVolumeCurve = CursorUtil.getColumnIndexOrThrow(_cursor, "autoVolumeCurve");
          final int _cursorIndexOfImperialUnits = CursorUtil.getColumnIndexOrThrow(_cursor, "imperialUnits");
          final int _cursorIndexOfVolumeKeysEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeKeysEnabled");
          final int _cursorIndexOfVolumeUpClick = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeUpClick");
          final int _cursorIndexOfVolumeUpHold = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeUpHold");
          final int _cursorIndexOfVolumeDownClick = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeDownClick");
          final int _cursorIndexOfVolumeDownHold = CursorUtil.getColumnIndexOrThrow(_cursor, "volumeDownHold");
          final AppSettings _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpLastDeviceAddress;
            if (_cursor.isNull(_cursorIndexOfLastDeviceAddress)) {
              _tmpLastDeviceAddress = null;
            } else {
              _tmpLastDeviceAddress = _cursor.getString(_cursorIndexOfLastDeviceAddress);
            }
            final String _tmpLastDeviceName;
            if (_cursor.isNull(_cursorIndexOfLastDeviceName)) {
              _tmpLastDeviceName = null;
            } else {
              _tmpLastDeviceName = _cursor.getString(_cursorIndexOfLastDeviceName);
            }
            final boolean _tmpAutoConnect;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfAutoConnect);
            _tmpAutoConnect = _tmp != 0;
            final float _tmpTiltbackSpeedKmh;
            _tmpTiltbackSpeedKmh = _cursor.getFloat(_cursorIndexOfTiltbackSpeedKmh);
            final float _tmpAlarmSpeedKmh;
            _tmpAlarmSpeedKmh = _cursor.getFloat(_cursorIndexOfAlarmSpeedKmh);
            final float _tmpSafetyTiltbackKmh;
            _tmpSafetyTiltbackKmh = _cursor.getFloat(_cursorIndexOfSafetyTiltbackKmh);
            final float _tmpSafetyAlarmKmh;
            _tmpSafetyAlarmKmh = _cursor.getFloat(_cursorIndexOfSafetyAlarmKmh);
            final float _tmpNormalTiltbackKmh;
            _tmpNormalTiltbackKmh = _cursor.getFloat(_cursorIndexOfNormalTiltbackKmh);
            final float _tmpNormalBeepKmh;
            _tmpNormalBeepKmh = _cursor.getFloat(_cursorIndexOfNormalBeepKmh);
            final boolean _tmpVoiceEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfVoiceEnabled);
            _tmpVoiceEnabled = _tmp_1 != 0;
            final int _tmpVoiceIntervalSeconds;
            _tmpVoiceIntervalSeconds = _cursor.getInt(_cursorIndexOfVoiceIntervalSeconds);
            final float _tmpVoiceSpeechRate;
            _tmpVoiceSpeechRate = _cursor.getFloat(_cursorIndexOfVoiceSpeechRate);
            final String _tmpVoiceLocale;
            _tmpVoiceLocale = _cursor.getString(_cursorIndexOfVoiceLocale);
            final boolean _tmpVoiceReportSpeed;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfVoiceReportSpeed);
            _tmpVoiceReportSpeed = _tmp_2 != 0;
            final boolean _tmpVoiceReportBattery;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfVoiceReportBattery);
            _tmpVoiceReportBattery = _tmp_3 != 0;
            final boolean _tmpVoiceReportTemp;
            final int _tmp_4;
            _tmp_4 = _cursor.getInt(_cursorIndexOfVoiceReportTemp);
            _tmpVoiceReportTemp = _tmp_4 != 0;
            final boolean _tmpVoiceReportPwm;
            final int _tmp_5;
            _tmp_5 = _cursor.getInt(_cursorIndexOfVoiceReportPwm);
            _tmpVoiceReportPwm = _tmp_5 != 0;
            final boolean _tmpVoiceReportDistance;
            final int _tmp_6;
            _tmp_6 = _cursor.getInt(_cursorIndexOfVoiceReportDistance);
            _tmpVoiceReportDistance = _tmp_6 != 0;
            final boolean _tmpTriggerReportSpeed;
            final int _tmp_7;
            _tmp_7 = _cursor.getInt(_cursorIndexOfTriggerReportSpeed);
            _tmpTriggerReportSpeed = _tmp_7 != 0;
            final boolean _tmpTriggerReportBattery;
            final int _tmp_8;
            _tmp_8 = _cursor.getInt(_cursorIndexOfTriggerReportBattery);
            _tmpTriggerReportBattery = _tmp_8 != 0;
            final boolean _tmpTriggerReportTemp;
            final int _tmp_9;
            _tmp_9 = _cursor.getInt(_cursorIndexOfTriggerReportTemp);
            _tmpTriggerReportTemp = _tmp_9 != 0;
            final boolean _tmpTriggerReportPwm;
            final int _tmp_10;
            _tmp_10 = _cursor.getInt(_cursorIndexOfTriggerReportPwm);
            _tmpTriggerReportPwm = _tmp_10 != 0;
            final boolean _tmpTriggerReportDistance;
            final int _tmp_11;
            _tmp_11 = _cursor.getInt(_cursorIndexOfTriggerReportDistance);
            _tmpTriggerReportDistance = _tmp_11 != 0;
            final boolean _tmpVoiceReportRecording;
            final int _tmp_12;
            _tmp_12 = _cursor.getInt(_cursorIndexOfVoiceReportRecording);
            _tmpVoiceReportRecording = _tmp_12 != 0;
            final boolean _tmpTriggerReportRecording;
            final int _tmp_13;
            _tmp_13 = _cursor.getInt(_cursorIndexOfTriggerReportRecording);
            _tmpTriggerReportRecording = _tmp_13 != 0;
            final String _tmpVoiceReportOrder;
            _tmpVoiceReportOrder = _cursor.getString(_cursorIndexOfVoiceReportOrder);
            final boolean _tmpAnnounceWheelLock;
            final int _tmp_14;
            _tmp_14 = _cursor.getInt(_cursorIndexOfAnnounceWheelLock);
            _tmpAnnounceWheelLock = _tmp_14 != 0;
            final boolean _tmpAnnounceLights;
            final int _tmp_15;
            _tmp_15 = _cursor.getInt(_cursorIndexOfAnnounceLights);
            _tmpAnnounceLights = _tmp_15 != 0;
            final boolean _tmpAnnounceRecording;
            final int _tmp_16;
            _tmp_16 = _cursor.getInt(_cursorIndexOfAnnounceRecording);
            _tmpAnnounceRecording = _tmp_16 != 0;
            final boolean _tmpAnnounceConnection;
            final int _tmp_17;
            _tmp_17 = _cursor.getInt(_cursorIndexOfAnnounceConnection);
            _tmpAnnounceConnection = _tmp_17 != 0;
            final boolean _tmpAnnounceGps;
            final int _tmp_18;
            _tmp_18 = _cursor.getInt(_cursorIndexOfAnnounceGps);
            _tmpAnnounceGps = _tmp_18 != 0;
            final boolean _tmpAnnounceSafetyMode;
            final int _tmp_19;
            _tmp_19 = _cursor.getInt(_cursorIndexOfAnnounceSafetyMode);
            _tmpAnnounceSafetyMode = _tmp_19 != 0;
            final boolean _tmpAutoRecord;
            final int _tmp_20;
            _tmp_20 = _cursor.getInt(_cursorIndexOfAutoRecord);
            _tmpAutoRecord = _tmp_20 != 0;
            final String _tmpFlic1Address;
            if (_cursor.isNull(_cursorIndexOfFlic1Address)) {
              _tmpFlic1Address = null;
            } else {
              _tmpFlic1Address = _cursor.getString(_cursorIndexOfFlic1Address);
            }
            final String _tmpFlic1Name;
            _tmpFlic1Name = _cursor.getString(_cursorIndexOfFlic1Name);
            final String _tmpFlic1Click;
            _tmpFlic1Click = _cursor.getString(_cursorIndexOfFlic1Click);
            final String _tmpFlic1DoubleClick;
            _tmpFlic1DoubleClick = _cursor.getString(_cursorIndexOfFlic1DoubleClick);
            final String _tmpFlic1Hold;
            _tmpFlic1Hold = _cursor.getString(_cursorIndexOfFlic1Hold);
            final String _tmpFlic2Address;
            if (_cursor.isNull(_cursorIndexOfFlic2Address)) {
              _tmpFlic2Address = null;
            } else {
              _tmpFlic2Address = _cursor.getString(_cursorIndexOfFlic2Address);
            }
            final String _tmpFlic2Name;
            _tmpFlic2Name = _cursor.getString(_cursorIndexOfFlic2Name);
            final String _tmpFlic2Click;
            _tmpFlic2Click = _cursor.getString(_cursorIndexOfFlic2Click);
            final String _tmpFlic2DoubleClick;
            _tmpFlic2DoubleClick = _cursor.getString(_cursorIndexOfFlic2DoubleClick);
            final String _tmpFlic2Hold;
            _tmpFlic2Hold = _cursor.getString(_cursorIndexOfFlic2Hold);
            final boolean _tmpAutoLightsEnabled;
            final int _tmp_21;
            _tmp_21 = _cursor.getInt(_cursorIndexOfAutoLightsEnabled);
            _tmpAutoLightsEnabled = _tmp_21 != 0;
            final int _tmpAutoLightsOnMinutesBefore;
            _tmpAutoLightsOnMinutesBefore = _cursor.getInt(_cursorIndexOfAutoLightsOnMinutesBefore);
            final int _tmpAutoLightsOffMinutesAfter;
            _tmpAutoLightsOffMinutesAfter = _cursor.getInt(_cursorIndexOfAutoLightsOffMinutesAfter);
            final boolean _tmpAutoVolumeEnabled;
            final int _tmp_22;
            _tmp_22 = _cursor.getInt(_cursorIndexOfAutoVolumeEnabled);
            _tmpAutoVolumeEnabled = _tmp_22 != 0;
            final String _tmpAutoVolumeCurve;
            _tmpAutoVolumeCurve = _cursor.getString(_cursorIndexOfAutoVolumeCurve);
            final boolean _tmpImperialUnits;
            final int _tmp_23;
            _tmp_23 = _cursor.getInt(_cursorIndexOfImperialUnits);
            _tmpImperialUnits = _tmp_23 != 0;
            final boolean _tmpVolumeKeysEnabled;
            final int _tmp_24;
            _tmp_24 = _cursor.getInt(_cursorIndexOfVolumeKeysEnabled);
            _tmpVolumeKeysEnabled = _tmp_24 != 0;
            final String _tmpVolumeUpClick;
            _tmpVolumeUpClick = _cursor.getString(_cursorIndexOfVolumeUpClick);
            final String _tmpVolumeUpHold;
            _tmpVolumeUpHold = _cursor.getString(_cursorIndexOfVolumeUpHold);
            final String _tmpVolumeDownClick;
            _tmpVolumeDownClick = _cursor.getString(_cursorIndexOfVolumeDownClick);
            final String _tmpVolumeDownHold;
            _tmpVolumeDownHold = _cursor.getString(_cursorIndexOfVolumeDownHold);
            _result = new AppSettings(_tmpId,_tmpLastDeviceAddress,_tmpLastDeviceName,_tmpAutoConnect,_tmpTiltbackSpeedKmh,_tmpAlarmSpeedKmh,_tmpSafetyTiltbackKmh,_tmpSafetyAlarmKmh,_tmpNormalTiltbackKmh,_tmpNormalBeepKmh,_tmpVoiceEnabled,_tmpVoiceIntervalSeconds,_tmpVoiceSpeechRate,_tmpVoiceLocale,_tmpVoiceReportSpeed,_tmpVoiceReportBattery,_tmpVoiceReportTemp,_tmpVoiceReportPwm,_tmpVoiceReportDistance,_tmpTriggerReportSpeed,_tmpTriggerReportBattery,_tmpTriggerReportTemp,_tmpTriggerReportPwm,_tmpTriggerReportDistance,_tmpVoiceReportRecording,_tmpTriggerReportRecording,_tmpVoiceReportOrder,_tmpAnnounceWheelLock,_tmpAnnounceLights,_tmpAnnounceRecording,_tmpAnnounceConnection,_tmpAnnounceGps,_tmpAnnounceSafetyMode,_tmpAutoRecord,_tmpFlic1Address,_tmpFlic1Name,_tmpFlic1Click,_tmpFlic1DoubleClick,_tmpFlic1Hold,_tmpFlic2Address,_tmpFlic2Name,_tmpFlic2Click,_tmpFlic2DoubleClick,_tmpFlic2Hold,_tmpAutoLightsEnabled,_tmpAutoLightsOnMinutesBefore,_tmpAutoLightsOffMinutesAfter,_tmpAutoVolumeEnabled,_tmpAutoVolumeCurve,_tmpImperialUnits,_tmpVolumeKeysEnabled,_tmpVolumeUpClick,_tmpVolumeUpHold,_tmpVolumeDownClick,_tmpVolumeDownHold);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
