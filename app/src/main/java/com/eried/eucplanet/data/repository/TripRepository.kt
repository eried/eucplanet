package com.eried.eucplanet.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.CsvWriter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripDao: TripDao,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) {
    companion object {
        private const val TAG = "TripRepo"
        private const val RECORD_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // Id of the TripRecord currently being recorded (null when idle). Used so the trip detail
    // screen can tell whether it is viewing the live-recording trip and animate the marker.
    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    val allTrips: Flow<List<TripRecord>> = tripDao.observeAll()
    val tripCount: Flow<Int> = tripDao.observeCount()

    private var csvWriter: CsvWriter? = null
    private var currentTrip: TripRecord? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    private var locationFixCount = 0
    private var locationUpdatesActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            locationFixCount++
            // Log first fix and then every 30th update so we can see GPS is alive without spamming.
            if (locationFixCount == 1 || locationFixCount % 30 == 0) {
                Log.i(
                    TAG,
                    "GPS fix #$locationFixCount lat=${"%.6f".format(loc.latitude)} " +
                            "lon=${"%.6f".format(loc.longitude)} " +
                            "acc=${"%.1f".format(loc.accuracy)}m " +
                            "speed=${if (loc.hasSpeed()) "%.1f m/s".format(loc.speed) else "n/a"}"
                )
            }
            _currentLocation.value = loc
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (locationUpdatesActive) {
            Log.d(TAG, "Location updates already active, skipping")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationUpdatesActive = true
            Log.i(TAG, "Location updates started (PRIORITY_HIGH_ACCURACY, 1Hz)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesActive = false
        Log.i(TAG, "Location updates stopped (received $locationFixCount fixes this session)")
    }

    fun getTripsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun startRecording() {
        if (_recording.value) return

        // Sanity-check location permission at recording start so missing permission is obvious in logs.
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Starting recording WITHOUT location permission — trip will have no GPS")
        }

        // Ensure location updates are running — may already be started by WheelService,
        // but we also want GPS when recording without a wheel connected.
        startLocationUpdates()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "trip_$dateStr.csv"
        val file = File(getTripsDir(), fileName)

        val writer = CsvWriter(file)
        writer.open()
        csvWriter = writer

        val trip = TripRecord(fileName = fileName)
        val id = tripDao.insert(trip)
        currentTrip = trip.copy(id = id)
        _currentTripId.value = id

        _recording.value = true
        Log.i(TAG, "Recording started: $fileName")
        scope.launch {
            val s = settingsRepository.get()
            if (s.announceRecording) voiceService.announceEvent(context.getString(R.string.voice_recording_started))
        }

        // Periodic write loop
        recordJob = scope.launch {
            var rowsWritten = 0
            var rowsWithGps = 0
            while (_recording.value) {
                val data = wheelRepository.wheelData.value
                val location = _currentLocation.value
                csvWriter?.writeRow(data, location)
                rowsWritten++
                if (location != null) rowsWithGps++
                if (rowsWritten % 30 == 0) {
                    Log.i(TAG, "Recording: $rowsWritten rows ($rowsWithGps with GPS)")
                }
                kotlinx.coroutines.delay(RECORD_INTERVAL_MS)
            }
            Log.i(TAG, "Recorder loop ending: $rowsWritten rows, $rowsWithGps with GPS")
        }
    }

    suspend fun stopRecording() {
        if (!_recording.value) return
        _recording.value = false
        recordJob?.cancel()
        recordJob = null

        csvWriter?.close()
        csvWriter = null

        val appSettings = settingsRepository.get()
        val willSync = appSettings.syncFolderUri != null
        currentTrip?.let { trip ->
            val data = wheelRepository.wheelData.value
            tripDao.update(
                trip.copy(
                    endTime = System.currentTimeMillis(),
                    distanceKm = data.tripDistance,
                    uploadStatus = if (willSync) 1 else 0
                )
            )
        }
        currentTrip = null
        _currentTripId.value = null
        Log.i(TAG, "Recording stopped")
        if (willSync) syncManager.enqueueTripUpload(appSettings)
        scope.launch {
            if (appSettings.announceRecording) voiceService.announceEvent(context.getString(R.string.voice_recording_finished))
        }
    }

    suspend fun deleteTrip(trip: TripRecord) {
        tripDao.delete(trip)
        val file = File(getTripsDir(), trip.fileName)
        if (file.exists()) file.delete()
    }

    suspend fun insertTrip(trip: TripRecord): Long = tripDao.insert(trip)

    suspend fun getTripById(id: Long): TripRecord? = tripDao.getById(id)

    suspend fun clearAll() {
        if (_recording.value) stopRecording()
        val dir = getTripsDir()
        // Delete all CSV and DBB files
        dir.listFiles()?.forEach { f ->
            if (f.extension.lowercase() in listOf("csv", "dbb")) f.delete()
        }
        tripDao.deleteAll()
    }

    fun getTripFile(trip: TripRecord): File = File(getTripsDir(), trip.fileName)
}
