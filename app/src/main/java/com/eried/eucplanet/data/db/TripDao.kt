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

    /**
     * Trips eligible for an eucstats upload, newest first. Three buckets:
     *   1 = previously enqueued, never finished (could be a worker that got
     *       killed or a partially-completed upload).
     *   3 = previously failed (auto-retry on the next sweep).
     *   0 = "orphaned": a live-recorded trip (tripUuid set) that finished
     *       cleanly but whose finalize hook never enqueued it (the trip-231
     *       symptom: finalize early-returned because the rider had no sync
     *       folder configured, even though eucstats was on). Catches the
     *       trip on the next sweep so the rider doesn't have to do anything.
     *
     * Pre-cloud / imported trips never enter this set: they have tripUuid =
     * null and the WHERE clause excludes them.
     */
    @Query(
        "SELECT * FROM trips " +
            "WHERE endTime IS NOT NULL " +
            "AND tripUuid IS NOT NULL " +
            "AND eucstatsStatus IN (0, 1, 3) " +
            "ORDER BY startTime DESC"
    )
    suspend fun getPendingEucstatsUploads(): List<TripRecord>
}
