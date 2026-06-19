package com.eried.eucplanet.ui.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class LatLon(val latitude: Double, val longitude: Double)

data class TripDataPoint(
    val date: String,
    val speed: Float,
    val voltage: Float,
    val temperature: Float,
    val battery: Int,
    val altitude: Float,
    val latitude: Double,
    val longitude: Double,
    val totalMileage: Float,
    /** Phone-derived GPS speed in km/h, parsed from the legacy `GPS speed` column. 0 if absent. */
    val gpsSpeed: Float = 0f,
    /** External-GPS-box speed in km/h (RaceBox). NaN when the row's column was empty. */
    val extGpsSpeed: Float = Float.NaN,
    /** Wheel current in amps, signed (negative = regen braking). NaN when the trip's CSV predates the column or the cell is blank. */
    val current: Float = Float.NaN,
    /** PWM / motor load in percent. NaN when the trip's CSV predates the column or the cell is blank. */
    val pwm: Float = Float.NaN
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val wheelRepository: com.eried.eucplanet.data.repository.WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val syncManager: com.eried.eucplanet.data.sync.SyncManager,
    private val eucStatsRepository: com.eried.eucplanet.data.eucstats.EucStatsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "RecordingVM"
    }

    private val _onlineRetryRunning = MutableStateFlow(false)
    val onlineRetryRunning: StateFlow<Boolean> = _onlineRetryRunning.asStateFlow()

    /**
     * Foreground retry of any pending / failed online uploads. Mirrors the Settings
     * "Sync all" path so a tap on the orange or red cloud icon actually does
     * something now instead of just enqueueing a WorkManager job that the OS may
     * defer for minutes. A background worker is still enqueued as a safety net.
     */
    fun retryOnlineUploads() {
        if (_onlineRetryRunning.value) return
        viewModelScope.launch {
            _onlineRetryRunning.value = true
            try {
                val settings = settingsRepository.get()
                // Prereqs gone since the icon was shown (unlinked, deleted,
                // folder lost). Don't attempt the upload and don't relight the
                // worker safety net — it would just bail at the gate anyway.
                if (!settings.onlineUploadEnabled || syncManager.riderStoreId.value == null) {
                    _toasts.send(context.getString(R.string.online_upload_sync_nothing))
                    return@launch
                }
                _toasts.send(context.getString(R.string.online_status_pending))
                val result = eucStatsRepository.syncPendingNow { _, _ -> }
                val msg = when {
                    result.total == 0 -> context.getString(R.string.online_upload_sync_nothing)
                    result.allFailed -> context.getString(R.string.online_status_failed)
                    else -> context.getString(R.string.online_upload_sync_done, result.uploaded)
                }
                _toasts.send(msg)
                // Background safety net for the next network change. Skipped when
                // we early-bailed above so we don't queue work doomed to no-op.
                syncManager.enqueueEucStatsUpload(settings)
            } finally {
                _onlineRetryRunning.value = false
            }
        }
    }

    val recording: StateFlow<Boolean> = tripRepository.recording

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "kmh")

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "km")

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "C")

    /**
     * Id of the just-stopped trip waiting in the 10s discard-grace window. The trip
     * row for this id shows an hourglass instead of the upload-success tick. Tapping
     * delete on that row goes through the regular confirmation dialog;
     * TripRepository.deleteTrip cancels the pending grace if the deleted trip is
     * the pending one.
     */
    val pendingTripId: StateFlow<Long?> = tripRepository.pendingTripId

    // Current GPS fix as (lat, lon) pair so the trip detail screen can animate a live marker.
    val liveLocation: StateFlow<LatLon?> = tripRepository.currentLocation
        .map { loc -> loc?.let { LatLon(it.latitude, it.longitude) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // True while the given trip is the one currently being recorded.
    fun isTripLiveRecording(trip: TripRecord): StateFlow<Boolean> =
        tripRepository.currentTripId
            .map { it != null && it == trip.id }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    val trips: StateFlow<List<TripRecord>> = tripRepository.allTrips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live label uses GPS-accumulated distance (same source the saved trip
    // distance uses at finalize), with the wheel's tripDistance as a fallback
    // only when GPS hasn't moved yet. Without this the label would freeze /
    // snap on BLE drop or wheel power-cycle while the time keeps ticking.
    val liveTripDistanceKm: StateFlow<Float> = kotlinx.coroutines.flow.combine(
        tripRepository.liveGpsDistanceKm,
        wheelRepository.wheelData.map { it.tripDistance }
    ) { gps, wheel -> if (gps > 0f) gps else wheel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val gpsFix: StateFlow<Boolean> = tripRepository.currentLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _locationPermissionGranted = MutableStateFlow(hasLocationPermission())
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()

    // Transient strings the ViewModel wants to surface as a snackbar (auto-stop
    // warning, etc.). The screen collects this flow once and pipes each emission
    // into its local SnackbarHostState. Replaces the previous native Toast that
    // sat behind the keyboard when triggered from a focused field.
    private val _toasts = Channel<String>(capacity = Channel.BUFFERED)
    val toasts: Flow<String> = _toasts.receiveAsFlow()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun refreshLocationPermission(): Boolean {
        val granted = hasLocationPermission()
        _locationPermissionGranted.value = granted
        return granted
    }

    suspend fun getTripById(id: Long): TripRecord? = tripRepository.getTripById(id)

    fun startGpsPreview() {
        if (!hasLocationPermission()) return
        tripRepository.startLocationUpdates()
    }

    fun stopGpsPreview() {
        // Only stop if not actively recording, recording needs GPS too
        if (!tripRepository.recording.value) {
            tripRepository.stopLocationUpdates()
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            if (tripRepository.recording.value) {
                tripRepository.stopRecording()
            } else {
                tripRepository.startRecording()
                val s = settingsRepository.get()
                if (s.autoRecord && s.autoRecordStartInMotion && s.autoRecordStopIdleSeconds > 0) {
                    val msg = context.getString(
                        R.string.recording_will_auto_stop,
                        s.autoRecordStopIdleSeconds
                    )
                    _toasts.send(msg)
                }
            }
        }
    }

    fun deleteTrip(trip: TripRecord) {
        viewModelScope.launch { tripRepository.deleteTrip(trip) }
    }

    fun shareTrip(trip: TripRecord) {
        viewModelScope.launch {
            val csvFile = tripRepository.getTripFile(trip)
            if (!csvFile.exists()) return@launch
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_trip_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun exportAllAsZip() {
        viewModelScope.launch {
            val tripsDir = tripRepository.getTripsDir()
            val csvFiles = tripsDir.listFiles { f -> f.extension == "csv" } ?: return@launch
            if (csvFiles.isEmpty()) return@launch

            val dbbFile = File(tripsDir, "trips_export.dbb")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dbbFile))).use { zos ->
                for (csv in csvFiles) {
                    zos.putNextEntry(ZipEntry(csv.name))
                    BufferedInputStream(FileInputStream(csv)).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dbbFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_trips_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // --- Import ---

    /**
     * Import trips from a .dbb (ZIP of CSVs) or .csv URI.
     * Returns the number of trips imported.
     */
    fun importTrips(uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val count = withContext(Dispatchers.IO) { importTripsFromUri(uri) }
                onResult(count)
            } finally {
                _importing.value = false
            }
        }
    }

    private suspend fun importTripsFromUri(uri: Uri): Int {
        val contentResolver = context.contentResolver
        // Resolve actual filename from content URI
        val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.lowercase() ?: uri.lastPathSegment?.substringAfterLast("/")?.lowercase() ?: ""

        Log.i(TAG, "Import: displayName=$displayName")
        var imported = 0

        try {
            if (displayName.endsWith(".dbb") || displayName.endsWith(".zip")) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name.lowercase()
                            if (!entry.isDirectory && name.endsWith(".csv") && !name.startsWith("__macosx")) {
                                if (importSingleCsv(entry.name, zis.readBytes())) imported++
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            } else if (displayName.endsWith(".csv")) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val csvName = displayName.substringAfterLast("/").ifEmpty { "imported_${System.currentTimeMillis()}.csv" }
                    if (importSingleCsv(csvName, inputStream.readBytes())) imported++
                }
            } else {
                // Unknown extension, try as ZIP first, fall back to CSV
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                        // ZIP magic bytes, treat as .dbb
                        ZipInputStream(BufferedInputStream(bytes.inputStream())).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val name = entry.name.lowercase()
                                if (!entry.isDirectory && name.endsWith(".csv") && !name.startsWith("__macosx")) {
                                    if (importSingleCsv(entry.name, zis.readBytes())) imported++
                                }
                                entry = zis.nextEntry
                            }
                        }
                    } else {
                        // Try as CSV
                        val csvName = displayName.ifEmpty { "imported_${System.currentTimeMillis()}.csv" }
                        if (importSingleCsv(csvName, bytes)) imported++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
        }
        return imported
    }

    private suspend fun importSingleCsv(originalName: String, csvBytes: ByteArray): Boolean {
        val tripsDir = tripRepository.getTripsDir()
        val baseName = originalName.removeSuffix(".csv").removeSuffix(".CSV")
        var targetFile = File(tripsDir, "$baseName.csv")
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(tripsDir, "${baseName}_$counter.csv")
            counter++
        }

        targetFile.writeBytes(csvBytes)

        val lines = csvBytes.decodeToString().lines()
        if (lines.size < 2) {
            targetFile.delete()
            return false
        }

        var startTime = System.currentTimeMillis()
        var endTime = startTime
        var distance = 0f

        try {
            // Detect column layout from header
            val header = lines[0].lowercase().split(",").map { it.trim() }
            val mileageIdx = header.indexOfFirst { it.contains("mileage") || it.contains("total mileage") }
                .takeIf { it >= 0 } ?: 8

            // Support multiple date formats
            val darknessFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(",")
                if (parts.size < 2) continue

                val dateStr = parts[0].trim()
                // Try ISO format first (2024-06-16T14:28:45.000000), then DarknessBot (dd.MM.yyyy HH:mm:ss.SSS)
                val parsed = try {
                    val trimmed = dateStr.substringBefore("T").let { datePart ->
                        if (dateStr.contains("T")) {
                            // ISO: trim microseconds
                            datePart + "T" + dateStr.substringAfter("T").let { timePart ->
                                if (timePart.contains(".")) timePart.substringBefore(".") else timePart
                            }
                        } else {
                            // European: trim milliseconds
                            val dotCount = dateStr.count { it == '.' }
                            if (dotCount > 2) dateStr.substringBeforeLast(".")
                            else dateStr
                        }
                    }
                    if (dateStr.contains("T")) isoFormat.parse(trimmed)
                    else darknessFormat.parse(trimmed)
                } catch (_: Exception) { null }

                if (parsed != null) {
                    if (i == 1) startTime = parsed.time
                    endTime = parsed.time
                }

                val mileage = parts.getOrNull(mileageIdx)?.toFloatOrNull() ?: 0f
                if (mileage > distance) distance = mileage
            }
        } catch (_: Exception) { }

        tripRepository.insertTrip(
            TripRecord(
                startTime = startTime,
                endTime = endTime,
                fileName = targetFile.name,
                distanceKm = distance
            )
        )
        return true
    }

    // --- Clear all ---

    fun clearAllTrips(onDone: () -> Unit) {
        viewModelScope.launch {
            tripRepository.clearAll()
            onDone()
        }
    }


    fun readTripData(trip: TripRecord): List<TripDataPoint> {
        val file = tripRepository.getTripFile(trip)
        if (!file.exists()) return emptyList()

        val points = mutableListOf<TripDataPoint>()
        BufferedReader(FileReader(file)).use { reader ->
            val headerLine = reader.readLine() ?: return emptyList()
            val headers = headerLine.lowercase().split(",").map { it.trim() }

            // Detect column indices from header to support DarknessBot and similar formats
            val iSpeed = headers.indexOfFirst { it == "speed" }.takeIf { it >= 0 } ?: 1
            val iVoltage = headers.indexOfFirst { it == "voltage" }.takeIf { it >= 0 } ?: 2
            val iTemp = headers.indexOfFirst { it == "temperature" }.takeIf { it >= 0 } ?: 3
            val iBattery = headers.indexOfFirst { it.contains("battery") }.takeIf { it >= 0 } ?: 4
            val iAltitude = headers.indexOfFirst { it == "altitude" }.takeIf { it >= 0 } ?: 5
            val iLat = headers.indexOfFirst { it == "latitude" }.takeIf { it >= 0 } ?: 6
            val iLon = headers.indexOfFirst { it == "longitude" }.takeIf { it >= 0 } ?: 7
            val iMileage = headers.indexOfFirst { it.contains("mileage") }.takeIf { it >= 0 } ?: 8
            // EUC Planet extensions; -1 when the column is absent (older trip files).
            val iGpsSpeed = headers.indexOfFirst { it == "gps speed" }
            val iExtGps = headers.indexOfFirst { it.startsWith("ext gps") || it.startsWith("ext_gps") || it == "external gps speed" }
            val iCurrent = headers.indexOfFirst { it == "current" }
            val iPwm = headers.indexOfFirst { it == "pwm" }

            var line = reader.readLine()
            while (line != null) {
                try {
                    val parts = line.split(",")
                    if (parts.size > maxOf(iSpeed, iVoltage, iTemp, iBattery)) {
                        val extRaw = if (iExtGps >= 0) parts.getOrNull(iExtGps)?.trim() else null
                        val extSpeed = extRaw
                            ?.takeIf { it.isNotEmpty() }
                            ?.toFloatOrNull()
                            ?: Float.NaN
                        // Current / PWM are EUC Planet extensions; absent column or
                        // blank cell both map to NaN ("not recorded").
                        val current = if (iCurrent >= 0)
                            parts.getOrNull(iCurrent)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
                        else Float.NaN
                        val pwm = if (iPwm >= 0)
                            parts.getOrNull(iPwm)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
                        else Float.NaN
                        points.add(
                            TripDataPoint(
                                date = parts[0],
                                speed = parts.getOrNull(iSpeed)?.toFloatOrNull() ?: 0f,
                                voltage = parts.getOrNull(iVoltage)?.toFloatOrNull() ?: 0f,
                                temperature = parts.getOrNull(iTemp)?.toFloatOrNull() ?: 0f,
                                battery = parts.getOrNull(iBattery)?.toFloatOrNull()?.toInt() ?: 0,
                                altitude = parts.getOrNull(iAltitude)?.toFloatOrNull() ?: 0f,
                                latitude = parts.getOrNull(iLat)?.toDoubleOrNull() ?: 0.0,
                                longitude = parts.getOrNull(iLon)?.toDoubleOrNull() ?: 0.0,
                                totalMileage = parts.getOrNull(iMileage)?.toFloatOrNull() ?: 0f,
                                gpsSpeed = if (iGpsSpeed >= 0) parts.getOrNull(iGpsSpeed)?.toFloatOrNull() ?: 0f else 0f,
                                extGpsSpeed = extSpeed,
                                current = current,
                                pwm = pwm
                            )
                        )
                    }
                } catch (_: Exception) { }
                line = reader.readLine()
            }
        }
        return points
    }
}
