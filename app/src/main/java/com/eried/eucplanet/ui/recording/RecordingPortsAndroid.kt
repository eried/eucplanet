package com.eried.eucplanet.ui.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.eried.eucplanet.R
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.SyncResult
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelChoice
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.platform.getString
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** TripRepository as the shared [RecordingTrips]. */
class AndroidRecordingTrips(private val trips: TripRepository) : RecordingTrips {
    // App-lifetime, like the repository whose fixes it mirrors.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val allTrips = trips.allTrips
    override val recording = trips.recording
    override val currentTripId = trips.currentTripId
    override val pendingTripId = trips.pendingTripId
    override val currentLocation = trips.currentLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.currentLocation.value?.toPhoneLocation())
    override val liveGpsDistanceKm = trips.liveGpsDistanceKm

    override fun startLocationUpdates() = trips.startLocationUpdates()
    override suspend fun startRecording() = trips.startRecording()
    override suspend fun stopRecording() = trips.stopRecording()

    override suspend fun getTripById(id: Long) = trips.getTripById(id)
    override suspend fun insertTrip(trip: TripRecord) = trips.insertTrip(trip)
    override suspend fun updateTrip(trip: TripRecord) = trips.updateTrip(trip)
    override suspend fun deleteTrip(trip: TripRecord) = trips.deleteTrip(trip)
    override suspend fun archiveTrips(trips: List<TripRecord>) = this.trips.archiveTrips(trips)
    override suspend fun archiveAllTrips() = trips.archiveAllTrips()
    override suspend fun clearAll() = trips.clearAll()

    override suspend fun knownWheels() = trips.knownWheels()
    override suspend fun changeTripWheel(trip: TripRecord, wheel: WheelChoice) {
        trips.changeTripWheel(trip, wheel)
    }
    override suspend fun renameTrip(trip: TripRecord, name: String) {
        trips.renameTrip(trip, name)
    }
    override suspend fun resyncTrip(tripId: Long) {
        trips.resyncTrip(tripId)
    }
    override suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>) = trips.splitTrip(source, boundariesMs)
    override suspend fun combineTrips(sources: List<TripRecord>) = trips.combineTrips(sources)
    override suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long) =
        trips.saveTripSection(source, startMs, endMs)

    override fun <R> readTripLines(trip: TripRecord, block: (Sequence<String>) -> R): R? {
        val file = trips.getTripFile(trip)
        if (!file.exists()) return null
        return file.bufferedReader().useLines(block)
    }

    override fun readTripBytes(trip: TripRecord): ByteArray? =
        trips.getTripFile(trip).takeIf { it.exists() }?.readBytes()

    override fun writeImportedTrip(baseName: String, bytes: ByteArray): String {
        val tripsDir = trips.getTripsDir()
        var targetFile = File(tripsDir, "$baseName.csv")
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(tripsDir, "${baseName}_$counter.csv")
            counter++
        }
        targetFile.writeBytes(bytes)
        return targetFile.name
    }

    override fun deleteTripFile(fileName: String) {
        File(trips.getTripsDir(), fileName).delete()
    }
}

class AndroidRecordingOnline(
    private val syncManager: SyncManager,
    private val eucStats: EucStatsRepository,
) : RecordingOnline {
    override val riderLinked get() = syncManager.riderStoreId.value != null
    override suspend fun syncPendingNow(): SyncResult = eucStats.syncPendingNow { _, _ -> }
    override fun enqueueUpload(settings: AppSettings) = syncManager.enqueueEucStatsUpload()
    override suspend fun refreshTripVerdict(trip: TripRecord) = eucStats.refreshTripVerdict(trip)
}

class AndroidRecordingPlatform(
    private val context: Context,
    private val trips: TripRepository,
) : RecordingPlatform {

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun shareTripFile(trip: TripRecord) {
        val csvFile = trips.getTripFile(trip)
        if (!csvFile.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_trip_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(
            intent, context.getString(R.string.share_trip_chooser)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun exportAllAsZip() {
        val tripsDir = trips.getTripsDir()
        val csvFiles = tripsDir.listFiles { f -> f.extension == "csv" } ?: return
        if (csvFiles.isEmpty()) return

        // Plain .zip so phones and desktops open it natively; the importer
        // (ours and the viewer's) accepts .zip and .dbb alike. Drop any
        // stale archive from the .dbb era.
        File(tripsDir, "trips_export.dbb").delete()
        val zipFile = File(tripsDir, "trips_export.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (csv in csvFiles) {
                zos.putNextEntry(ZipEntry(csv.name))
                BufferedInputStream(FileInputStream(csv)).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_trips_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A .dbb (ZIP of CSVs), a .zip or a .csv; an unknown extension is sniffed for the ZIP magic. */
    override suspend fun readImport(uri: String): List<ImportedFile> = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val contentResolver = context.contentResolver
        // Resolve actual filename from content URI
        val displayName = contentResolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.lowercase() ?: parsed.lastPathSegment?.substringAfterLast("/")?.lowercase() ?: ""

        Log.i(TAG, "Import: displayName=$displayName")
        val out = ArrayList<ImportedFile>()
        if (displayName.endsWith(".dbb") || displayName.endsWith(".zip")) {
            contentResolver.openInputStream(parsed)?.use { unzipCsvs(it, out) }
        } else if (displayName.endsWith(".csv")) {
            contentResolver.openInputStream(parsed)?.use { inputStream ->
                val csvName = displayName.substringAfterLast("/").ifEmpty { "imported_${System.currentTimeMillis()}.csv" }
                out.add(ImportedFile(csvName, inputStream.readBytes()))
            }
        } else {
            // Unknown extension, try as ZIP first, fall back to CSV
            contentResolver.openInputStream(parsed)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    // ZIP magic bytes, treat as .dbb
                    unzipCsvs(bytes.inputStream(), out)
                } else {
                    // Try as CSV
                    val csvName = displayName.ifEmpty { "imported_${System.currentTimeMillis()}.csv" }
                    out.add(ImportedFile(csvName, bytes))
                }
            }
        }
        out
    }

    private fun unzipCsvs(input: InputStream, out: MutableList<ImportedFile>) {
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && name.endsWith(".csv") && !name.startsWith("__macosx")) {
                    out.add(ImportedFile(entry.name, zis.readBytes()))
                }
                entry = zis.nextEntry
            }
        }
    }

    override fun text(res: StringResource, vararg args: Any): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args)

    private companion object {
        const val TAG = "RecordingVM"
    }
}
