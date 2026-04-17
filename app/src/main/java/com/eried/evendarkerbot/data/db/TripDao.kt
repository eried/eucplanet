package com.eried.evendarkerbot.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.eried.evendarkerbot.data.model.TripRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TripRecord>>

    @Insert
    suspend fun insert(trip: TripRecord): Long

    @Update
    suspend fun update(trip: TripRecord)

    @Delete
    suspend fun delete(trip: TripRecord)

    @Query("SELECT COUNT(*) FROM trips")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
