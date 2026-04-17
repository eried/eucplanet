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

    val allTrips: Flow<List<TripRecord>> = tripDao.observeAll()
    val tripCount: Flow<Int> = tripDao.observeCount()

    private var csvWriter: CsvWriter? = null
    private var currentTrip: TripRecord? = null
    private var recordJob: kotlinx.coroutines.Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            _currentLocation.value = result.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun getTripsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun startRecording() {
        if (_recording.value) return

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

        _recording.value = true
        Log.i(TAG, "Recording started: $fileName")
        scope.launch {
            val s = settingsRepository.get()
            if (s.announceRecording) voiceService.announceEvent(context.getString(R.string.voice_recording_started))
        }

        // Periodic write loop
        recordJob = scope.launch {
            while (_recording.value) {
                val data = wheelRepository.wheelData.value
                val location = _currentLocation.value
                csvWriter?.writeRow(data, location)
                kotlinx.coroutines.delay(RECORD_INTERVAL_MS)
            }
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

    suspend fun clearAll() {
        val dir = getTripsDir()
        // Delete all CSV and DBB files
        dir.listFiles()?.forEach { f ->
            if (f.extension.lowercase() in listOf("csv", "dbb")) f.delete()
        }
        tripDao.deleteAll()
    }

    fun getTripFile(trip: TripRecord): File = File(getTripsDir(), trip.fileName)
}
