package com.eried.evendarkerbot.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.eried.evendarkerbot.data.model.AlarmRule
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarm_rules ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<AlarmRule>>

    @Query("SELECT * FROM alarm_rules WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getEnabled(): List<AlarmRule>

    @Insert
    suspend fun insert(rule: AlarmRule): Long

    @Update
    suspend fun update(rule: AlarmRule)

    @Delete
    suspend fun delete(rule: AlarmRule)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM alarm_rules")
    suspend fun nextSortOrder(): Int
}
