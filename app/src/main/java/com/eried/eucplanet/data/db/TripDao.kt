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
    @Query("SELECT * FROM trips WHERE endTime IS NOT NULL AND uploadStatus IN (1, 3, 4) ORDER BY startTime DESC")
    suspend fun getPendingUploads(): List<TripRecord>

    /**
     * Status 4: mirror this into the backup folder, but never over something
     * already there.
     *
     * For a trip that arrived from Dropbox. The usual mirror overwrites,
     * because a locally recorded or edited trip is the authority on its own
     * file - but a download is not: if the folder already holds that name, its
     * copy could be a different version, and quietly replacing it would lose
     * whatever the rider had. Those are left for an explicit sync, where the
     * rider is asked which side wins.
     */
    @Query("UPDATE trips SET uploadStatus = 4 WHERE id = :id")
    suspend fun markMirrorIfAbsent(id: Long)

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

    /** Set (or clear, with null) the rider's custom trip name. Single-column
     *  update so it never disturbs upload status or timings. */
    @Query("UPDATE trips SET customName = :name WHERE id = :id")
    suspend fun updateCustomName(id: Long, name: String?)

    /** Re-flag a trip for folder upload after its file was edited in place
     *  (rename / change wheel). The folder worker only walks uploadStatus 1/3,
     *  so an already-uploaded (2) trip would otherwise never re-sync. */
    @Query("UPDATE trips SET uploadStatus = 1 WHERE id = :id")
    suspend fun markPendingFolderUpload(id: Long)

    /** Dropbox state by file name: the sync works in files, not row ids. */
    @Query("UPDATE trips SET dropboxStatus = :status, dropboxUploadedAt = :at WHERE fileName = :fileName")
    suspend fun setDropboxStatusByName(fileName: String, status: Int, at: Long?)

    @Query("UPDATE trips SET dropboxStatus = :status WHERE id = :id")
    suspend fun setDropboxStatus(id: Long, status: Int)

    /** Every wheel identity any trip carries. Feeds the trip-tools wheel picker
     *  so a wheel that was only ever imported, never paired, is still offered. */
    @Query("SELECT wheelMetaJson FROM trips WHERE wheelMetaJson IS NOT NULL")
    suspend fun allWheelMeta(): List<String>

    /** Finished trips whose wheel has never been read out of their file.
     *  Feeds the one-time backfill; rows it finds nothing in are stamped
     *  "{}" so they are never opened again. */
    @Query("SELECT * FROM trips WHERE wheelMetaJson IS NULL AND endTime IS NOT NULL")
    suspend fun tripsWithoutWheelMeta(): List<TripRecord>

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

    /**
     * Trips the server accepted but is still holding for review.
     *
     * The verdict is not final at upload: a held trip becomes "validated" once a human
     * approves it, and the upload response was our only look at it. Without re-reading
     * these, the rider keeps an "under review" cloud forever on a ride that is already
     * counting on the leaderboard.
     *
     * Green (validated) and still-uploading trips are excluded: only a held verdict can
     * change behind the app's back, so only these are worth asking about.
     *
     * [limit] bounds the cost, newest first. One check is a ~150 byte response, but a rider
     * whose trips are never reviewed would otherwise re-ask about all of them on every
     * background sweep. The explicit "Sync all" passes no meaningful bound, because the
     * rider asked for it.
     */
    @Query(
        "SELECT * FROM trips " +
            "WHERE tripUuid IS NOT NULL " +
            "AND eucstatsStatus = 2 " +
            "AND eucstatsValidation = 'flagged' " +
            "ORDER BY startTime DESC " +
            "LIMIT :limit"
    )
    suspend fun getHeldEucstatsTrips(limit: Int): List<TripRecord>

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
