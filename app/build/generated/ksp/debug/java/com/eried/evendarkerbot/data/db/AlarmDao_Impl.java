package com.eried.evendarkerbot.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.eried.evendarkerbot.data.model.AlarmRule;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AlarmDao_Impl implements AlarmDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<AlarmRule> __insertionAdapterOfAlarmRule;

  private final EntityDeletionOrUpdateAdapter<AlarmRule> __deletionAdapterOfAlarmRule;

  private final EntityDeletionOrUpdateAdapter<AlarmRule> __updateAdapterOfAlarmRule;

  public AlarmDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfAlarmRule = new EntityInsertionAdapter<AlarmRule>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `alarm_rules` (`id`,`name`,`enabled`,`sortOrder`,`metric`,`comparator`,`threshold`,`beepEnabled`,`beepFrequency`,`beepDurationMs`,`beepCount`,`voiceEnabled`,`voiceText`,`vibrateEnabled`,`vibrateDurationMs`,`cooldownSeconds`,`repeatWhileActive`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AlarmRule entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        final int _tmp = entity.getEnabled() ? 1 : 0;
        statement.bindLong(3, _tmp);
        statement.bindLong(4, entity.getSortOrder());
        statement.bindString(5, entity.getMetric());
        statement.bindString(6, entity.getComparator());
        statement.bindDouble(7, entity.getThreshold());
        final int _tmp_1 = entity.getBeepEnabled() ? 1 : 0;
        statement.bindLong(8, _tmp_1);
        statement.bindLong(9, entity.getBeepFrequency());
        statement.bindLong(10, entity.getBeepDurationMs());
        statement.bindLong(11, entity.getBeepCount());
        final int _tmp_2 = entity.getVoiceEnabled() ? 1 : 0;
        statement.bindLong(12, _tmp_2);
        statement.bindString(13, entity.getVoiceText());
        final int _tmp_3 = entity.getVibrateEnabled() ? 1 : 0;
        statement.bindLong(14, _tmp_3);
        statement.bindLong(15, entity.getVibrateDurationMs());
        statement.bindLong(16, entity.getCooldownSeconds());
        final int _tmp_4 = entity.getRepeatWhileActive() ? 1 : 0;
        statement.bindLong(17, _tmp_4);
      }
    };
    this.__deletionAdapterOfAlarmRule = new EntityDeletionOrUpdateAdapter<AlarmRule>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `alarm_rules` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AlarmRule entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfAlarmRule = new EntityDeletionOrUpdateAdapter<AlarmRule>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `alarm_rules` SET `id` = ?,`name` = ?,`enabled` = ?,`sortOrder` = ?,`metric` = ?,`comparator` = ?,`threshold` = ?,`beepEnabled` = ?,`beepFrequency` = ?,`beepDurationMs` = ?,`beepCount` = ?,`voiceEnabled` = ?,`voiceText` = ?,`vibrateEnabled` = ?,`vibrateDurationMs` = ?,`cooldownSeconds` = ?,`repeatWhileActive` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AlarmRule entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        final int _tmp = entity.getEnabled() ? 1 : 0;
        statement.bindLong(3, _tmp);
        statement.bindLong(4, entity.getSortOrder());
        statement.bindString(5, entity.getMetric());
        statement.bindString(6, entity.getComparator());
        statement.bindDouble(7, entity.getThreshold());
        final int _tmp_1 = entity.getBeepEnabled() ? 1 : 0;
        statement.bindLong(8, _tmp_1);
        statement.bindLong(9, entity.getBeepFrequency());
        statement.bindLong(10, entity.getBeepDurationMs());
        statement.bindLong(11, entity.getBeepCount());
        final int _tmp_2 = entity.getVoiceEnabled() ? 1 : 0;
        statement.bindLong(12, _tmp_2);
        statement.bindString(13, entity.getVoiceText());
        final int _tmp_3 = entity.getVibrateEnabled() ? 1 : 0;
        statement.bindLong(14, _tmp_3);
        statement.bindLong(15, entity.getVibrateDurationMs());
        statement.bindLong(16, entity.getCooldownSeconds());
        final int _tmp_4 = entity.getRepeatWhileActive() ? 1 : 0;
        statement.bindLong(17, _tmp_4);
        statement.bindLong(18, entity.getId());
      }
    };
  }

  @Override
  public Object insert(final AlarmRule rule, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfAlarmRule.insertAndReturnId(rule);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final AlarmRule rule, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfAlarmRule.handle(rule);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final AlarmRule rule, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfAlarmRule.handle(rule);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<AlarmRule>> observeAll() {
    final String _sql = "SELECT * FROM alarm_rules ORDER BY sortOrder ASC, id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"alarm_rules"}, new Callable<List<AlarmRule>>() {
      @Override
      @NonNull
      public List<AlarmRule> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSortOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "sortOrder");
          final int _cursorIndexOfMetric = CursorUtil.getColumnIndexOrThrow(_cursor, "metric");
          final int _cursorIndexOfComparator = CursorUtil.getColumnIndexOrThrow(_cursor, "comparator");
          final int _cursorIndexOfThreshold = CursorUtil.getColumnIndexOrThrow(_cursor, "threshold");
          final int _cursorIndexOfBeepEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "beepEnabled");
          final int _cursorIndexOfBeepFrequency = CursorUtil.getColumnIndexOrThrow(_cursor, "beepFrequency");
          final int _cursorIndexOfBeepDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "beepDurationMs");
          final int _cursorIndexOfBeepCount = CursorUtil.getColumnIndexOrThrow(_cursor, "beepCount");
          final int _cursorIndexOfVoiceEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceEnabled");
          final int _cursorIndexOfVoiceText = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceText");
          final int _cursorIndexOfVibrateEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "vibrateEnabled");
          final int _cursorIndexOfVibrateDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "vibrateDurationMs");
          final int _cursorIndexOfCooldownSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "cooldownSeconds");
          final int _cursorIndexOfRepeatWhileActive = CursorUtil.getColumnIndexOrThrow(_cursor, "repeatWhileActive");
          final List<AlarmRule> _result = new ArrayList<AlarmRule>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AlarmRule _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final boolean _tmpEnabled;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp != 0;
            final int _tmpSortOrder;
            _tmpSortOrder = _cursor.getInt(_cursorIndexOfSortOrder);
            final String _tmpMetric;
            _tmpMetric = _cursor.getString(_cursorIndexOfMetric);
            final String _tmpComparator;
            _tmpComparator = _cursor.getString(_cursorIndexOfComparator);
            final float _tmpThreshold;
            _tmpThreshold = _cursor.getFloat(_cursorIndexOfThreshold);
            final boolean _tmpBeepEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfBeepEnabled);
            _tmpBeepEnabled = _tmp_1 != 0;
            final int _tmpBeepFrequency;
            _tmpBeepFrequency = _cursor.getInt(_cursorIndexOfBeepFrequency);
            final int _tmpBeepDurationMs;
            _tmpBeepDurationMs = _cursor.getInt(_cursorIndexOfBeepDurationMs);
            final int _tmpBeepCount;
            _tmpBeepCount = _cursor.getInt(_cursorIndexOfBeepCount);
            final boolean _tmpVoiceEnabled;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfVoiceEnabled);
            _tmpVoiceEnabled = _tmp_2 != 0;
            final String _tmpVoiceText;
            _tmpVoiceText = _cursor.getString(_cursorIndexOfVoiceText);
            final boolean _tmpVibrateEnabled;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfVibrateEnabled);
            _tmpVibrateEnabled = _tmp_3 != 0;
            final int _tmpVibrateDurationMs;
            _tmpVibrateDurationMs = _cursor.getInt(_cursorIndexOfVibrateDurationMs);
            final int _tmpCooldownSeconds;
            _tmpCooldownSeconds = _cursor.getInt(_cursorIndexOfCooldownSeconds);
            final boolean _tmpRepeatWhileActive;
            final int _tmp_4;
            _tmp_4 = _cursor.getInt(_cursorIndexOfRepeatWhileActive);
            _tmpRepeatWhileActive = _tmp_4 != 0;
            _item = new AlarmRule(_tmpId,_tmpName,_tmpEnabled,_tmpSortOrder,_tmpMetric,_tmpComparator,_tmpThreshold,_tmpBeepEnabled,_tmpBeepFrequency,_tmpBeepDurationMs,_tmpBeepCount,_tmpVoiceEnabled,_tmpVoiceText,_tmpVibrateEnabled,_tmpVibrateDurationMs,_tmpCooldownSeconds,_tmpRepeatWhileActive);
            _result.add(_item);
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
  public Object getEnabled(final Continuation<? super List<AlarmRule>> $completion) {
    final String _sql = "SELECT * FROM alarm_rules WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<AlarmRule>>() {
      @Override
      @NonNull
      public List<AlarmRule> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "enabled");
          final int _cursorIndexOfSortOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "sortOrder");
          final int _cursorIndexOfMetric = CursorUtil.getColumnIndexOrThrow(_cursor, "metric");
          final int _cursorIndexOfComparator = CursorUtil.getColumnIndexOrThrow(_cursor, "comparator");
          final int _cursorIndexOfThreshold = CursorUtil.getColumnIndexOrThrow(_cursor, "threshold");
          final int _cursorIndexOfBeepEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "beepEnabled");
          final int _cursorIndexOfBeepFrequency = CursorUtil.getColumnIndexOrThrow(_cursor, "beepFrequency");
          final int _cursorIndexOfBeepDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "beepDurationMs");
          final int _cursorIndexOfBeepCount = CursorUtil.getColumnIndexOrThrow(_cursor, "beepCount");
          final int _cursorIndexOfVoiceEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceEnabled");
          final int _cursorIndexOfVoiceText = CursorUtil.getColumnIndexOrThrow(_cursor, "voiceText");
          final int _cursorIndexOfVibrateEnabled = CursorUtil.getColumnIndexOrThrow(_cursor, "vibrateEnabled");
          final int _cursorIndexOfVibrateDurationMs = CursorUtil.getColumnIndexOrThrow(_cursor, "vibrateDurationMs");
          final int _cursorIndexOfCooldownSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "cooldownSeconds");
          final int _cursorIndexOfRepeatWhileActive = CursorUtil.getColumnIndexOrThrow(_cursor, "repeatWhileActive");
          final List<AlarmRule> _result = new ArrayList<AlarmRule>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AlarmRule _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final boolean _tmpEnabled;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfEnabled);
            _tmpEnabled = _tmp != 0;
            final int _tmpSortOrder;
            _tmpSortOrder = _cursor.getInt(_cursorIndexOfSortOrder);
            final String _tmpMetric;
            _tmpMetric = _cursor.getString(_cursorIndexOfMetric);
            final String _tmpComparator;
            _tmpComparator = _cursor.getString(_cursorIndexOfComparator);
            final float _tmpThreshold;
            _tmpThreshold = _cursor.getFloat(_cursorIndexOfThreshold);
            final boolean _tmpBeepEnabled;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfBeepEnabled);
            _tmpBeepEnabled = _tmp_1 != 0;
            final int _tmpBeepFrequency;
            _tmpBeepFrequency = _cursor.getInt(_cursorIndexOfBeepFrequency);
            final int _tmpBeepDurationMs;
            _tmpBeepDurationMs = _cursor.getInt(_cursorIndexOfBeepDurationMs);
            final int _tmpBeepCount;
            _tmpBeepCount = _cursor.getInt(_cursorIndexOfBeepCount);
            final boolean _tmpVoiceEnabled;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfVoiceEnabled);
            _tmpVoiceEnabled = _tmp_2 != 0;
            final String _tmpVoiceText;
            _tmpVoiceText = _cursor.getString(_cursorIndexOfVoiceText);
            final boolean _tmpVibrateEnabled;
            final int _tmp_3;
            _tmp_3 = _cursor.getInt(_cursorIndexOfVibrateEnabled);
            _tmpVibrateEnabled = _tmp_3 != 0;
            final int _tmpVibrateDurationMs;
            _tmpVibrateDurationMs = _cursor.getInt(_cursorIndexOfVibrateDurationMs);
            final int _tmpCooldownSeconds;
            _tmpCooldownSeconds = _cursor.getInt(_cursorIndexOfCooldownSeconds);
            final boolean _tmpRepeatWhileActive;
            final int _tmp_4;
            _tmp_4 = _cursor.getInt(_cursorIndexOfRepeatWhileActive);
            _tmpRepeatWhileActive = _tmp_4 != 0;
            _item = new AlarmRule(_tmpId,_tmpName,_tmpEnabled,_tmpSortOrder,_tmpMetric,_tmpComparator,_tmpThreshold,_tmpBeepEnabled,_tmpBeepFrequency,_tmpBeepDurationMs,_tmpBeepCount,_tmpVoiceEnabled,_tmpVoiceText,_tmpVibrateEnabled,_tmpVibrateDurationMs,_tmpCooldownSeconds,_tmpRepeatWhileActive);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object nextSortOrder(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM alarm_rules";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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
