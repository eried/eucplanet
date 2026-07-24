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
     * Persist ONLY the wheel-identity metadata onto a row mid-recording. The full
     * identity is otherwise written just once, at stopRecording(); a ride that is
     * OOM-/force-killed before that runs would recover from its CSV with no wheel
     * ([finalizeUnfinishedTrips]). Flushing it here — the moment the wheel is
     * identified — means the row already carries it before any kill, and the
     * recovery sweep preserves it. Single-column update: never disturbs endTime,
     * distance, or upload status.
     */
    @Query("UPDATE trips SET wheelMetaJson = :json WHERE id = :id")
    suspend fun updateWheelMeta(id: Long, json: String?)

    /** Live-recorded trips with no end time. Normally just the one currently
     *  recording, but at cold start any left here are recordings a previous
     *  session was killed mid-flight (force-close / crash). Finalized from their
     *  CSV by the startup recovery sweep so they stop showing as 0 km / no-end. */
    @Query("SELECT * FROM trips WHERE endTime IS NULL")
    suspend fun getUnfinished(): List<TripRecord>

    @Query("SELECT * FROM trips WHERE fileName = :name LIMIT 1")
    suspend fun findByFileName(name: String): TripRecord?

    /** All trip CSV file names on record. Used to find orphan CSVs in the trips
     *  directory whose DB row went missing (e.g. after a DB rebuild). */
    @Query("SELECT fileName FROM trips")
    suspend fun allFileNames(): List<String>

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

    /** Clear unfinished eucstats statuses (pending / failed). Used when online
     *  uploads are toggled off or the sync folder is unlinked, so the orange /
     *  red cloud icon stops appearing for trips that can no longer upload.
     *  Status 2 (already on the leaderboard) is preserved because the server
     *  still has those trips and the user can rejoin the same rider id. */
    @Query("UPDATE trips SET eucstatsStatus = 0 WHERE eucstatsStatus IN (1, 3)")
    suspend fun resetUnfinishedEucstatsStatuses()

    /** Clear EVERY non-zero eucstats status. Used only when the account is
     *  deleted server-side; the green tick would otherwise advertise trips
     *  that no longer exist on the leaderboard. */
    @Query("UPDATE trips SET eucstatsStatus = 0 WHERE eucstatsStatus != 0")
    suspend fun resetAllEucstatsStatuses()
}
