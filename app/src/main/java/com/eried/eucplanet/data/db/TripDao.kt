package com.eried.eucplanet.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.eried.eucplanet.data.model.TripRecord
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

    /**
     * Trips that still need to be uploaded (pending or failed, and recording finished).
     * Returned newest-first so a single pass starts with the trip just completed.
     */
    @Query("SELECT * FROM trips WHERE endTime IS NOT NULL AND uploadStatus IN (1, 3) ORDER BY startTime DESC")
    suspend fun getPendingUploads(): List<TripRecord>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripRecord?
}
