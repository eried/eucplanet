package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.data.eucstats.SyncResult
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.WheelChoice
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * What the trip list and the trip viewer ask of the trip store: the recorder,
 * the trip rows and their CSV files. Android's is TripRepository; iOS has its
 * own recorder (app/IosTrips).
 */
interface RecordingTrips {
    val allTrips: Flow<List<TripRecord>>
    val recording: StateFlow<Boolean>
    val currentTripId: StateFlow<Long?>
    /** The trip waiting out its discard grace before it is synced, if any. */
    val pendingTripId: StateFlow<Long?>
    val currentLocation: StateFlow<PhoneLocation?>
    /** GPS distance of the trip being recorded, in km. */
    val liveGpsDistanceKm: StateFlow<Float>

    fun startLocationUpdates()
    suspend fun startRecording()
    suspend fun stopRecording()

    suspend fun getTripById(id: Long): TripRecord?
    suspend fun insertTrip(trip: TripRecord): Long
    suspend fun updateTrip(trip: TripRecord)
    suspend fun deleteTrip(trip: TripRecord)
    /** Deletes the trips here after moving their backup copies into the archive; how many went. */
    suspend fun archiveTrips(trips: List<TripRecord>): Int
    suspend fun archiveAllTrips(): Int
    suspend fun clearAll()

    suspend fun knownWheels(): List<WheelChoice>
    suspend fun changeTripWheel(trip: TripRecord, wheel: WheelChoice)
    suspend fun renameTrip(trip: TripRecord, name: String)
    suspend fun resyncTrip(tripId: Long)
    suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>): List<TripRecord>
    suspend fun combineTrips(sources: List<TripRecord>): TripRecord?
    suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long): TripRecord?

    /** The lines of the trip's CSV, header first, given to [block]; null when the file is gone. */
    fun <R> readTripLines(trip: TripRecord, block: (Sequence<String>) -> R): R?
    /** The trip's CSV as it is on disk; null when the file is gone. */
    fun readTripBytes(trip: TripRecord): ByteArray?
    /** Writes an imported CSV under a free name close to [baseName]; the file name it got. */
    fun writeImportedTrip(baseName: String, bytes: ByteArray): String
    fun deleteTripFile(fileName: String)
}

/** One file picked for import, a CSV or the CSVs from a .zip. */
class ImportedFile(val name: String, val bytes: ByteArray)

/** The phone's side of the trip list: its permission, its share sheet and its picker. */
interface RecordingPlatform {
    fun hasLocationPermission(): Boolean

    /** Shares the trip's CSV file through the system share sheet. */
    fun shareTripFile(trip: TripRecord)

    /** Shares a link or other text through the system share sheet. */
    fun shareText(text: String)

    /** Zips every trip CSV and shares the archive. */
    suspend fun exportAllAsZip()

    /** The CSVs in the file [uri] the picker returned, unpacked when it is a .zip or .dbb. */
    suspend fun readImport(uri: String): List<ImportedFile>

    /** A shared string in the rider's language, formatted like Android's getString. */
    fun text(res: StringResource, vararg args: Any): String
}

/** The eucstats leaderboard, as far as the trip list deals with it. */
interface RecordingOnline {
    /** A rider profile is linked on this phone. */
    val riderLinked: Boolean
    suspend fun syncPendingNow(): SyncResult
    /** Queues a background upload, the safety net for the next network change. */
    fun enqueueUpload(settings: AppSettings)
    /** The server's verdict on [trip] now: "validated", "rejected", "flagged", or null. */
    suspend fun refreshTripVerdict(trip: TripRecord): String?
}

/** What the trip list screen asks of the platform it is drawn on. */
interface RecordingHost {
    /** Rename, change wheel, split and combine can be offered. */
    val supportsTripTools: Boolean

    /** Swallows or handles the system back gesture while [enabled]. */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /** Asks for location access; [onResult] runs when the rider has answered. */
    @Composable
    fun rememberLocationPermissionRequest(onResult: () -> Unit): () -> Unit

    /** A file picker for trips to import (.csv, .zip, .dbb); [onPicked] gets the file's uri. */
    @Composable
    fun rememberTripImportPicker(onPicked: (String) -> Unit): () -> Unit
}
