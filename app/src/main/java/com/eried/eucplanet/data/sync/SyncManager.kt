package com.eried.eucplanet.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates cloud sync via the Storage Access Framework.
 *
 * The user picks a folder in any DocumentsProvider (OneDrive, Google Drive,
 * Dropbox, local device storage, etc.) with ACTION_OPEN_DOCUMENT_TREE. We take
 * persistable permission and store the tree URI in settings. Trip CSVs and a
 * `settings.json` backup are written through DocumentFile — no provider-specific
 * code, no OAuth, the cloud app handles the upload.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao
) {
    companion object {
        private const val TAG = "SyncManager"
        const val SETTINGS_BACKUP_NAME = "eucplanet_settings.json"
        const val TRIPS_SUBFOLDER = "trips"
        const val UPLOAD_WORK_NAME = "trip_upload"
    }

    // App-scoped so trip sync survives settings screen navigation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning.asStateFlow()

    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    private val _syncConflictPrompt = MutableStateFlow<Int?>(null)
    val syncConflictPrompt: StateFlow<Int?> = _syncConflictPrompt.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()
    fun consumeSyncResult() { _syncResult.value = null }

    private var conflictChoice: CompletableDeferred<SyncChoice>? = null

    fun resolveSyncConflict(choice: SyncChoice) { conflictChoice?.complete(choice) }
    fun cancelSyncConflict() { conflictChoice?.complete(SyncChoice.CANCEL) }

    fun startSync() {
        if (!_syncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                runSync()
            } finally {
                _syncProgress.value = null
                _syncConflictPrompt.value = null
                conflictChoice = null
                _syncRunning.value = false
            }
        }
    }

    private suspend fun runSync() {
        val settings = settingsRepository.get()
        if (settings.syncFolderUri == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val folderNames = listFolderTripNames(settings)
        if (folderNames == null) {
            _syncResult.value = SyncResult.NoFolder
            return
        }

        val dbTrips = tripDao.observeAll().first()
        val folderByLower = folderNames.associateBy { it.lowercase() }
        val dbByLower = dbTrips.associateBy { it.fileName.lowercase() }

        val conflictKeys = folderByLower.keys intersect dbByLower.keys
        val folderOnlyKeys = folderByLower.keys - dbByLower.keys
        val dbOnly = dbTrips.filter {
            it.endTime != null && it.fileName.lowercase() !in folderByLower.keys
        }

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictPrompt.value = conflictKeys.size
            choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            if (choice == SyncChoice.CANCEL) return
        }

        val toUpload = dbOnly.toMutableList()
        val toDownload = folderOnlyKeys.mapNotNull { folderByLower[it] }.toMutableList()
        when (choice) {
            SyncChoice.APP -> toUpload.addAll(conflictKeys.mapNotNull { dbByLower[it] })
            SyncChoice.FOLDER -> toDownload.addAll(conflictKeys.mapNotNull { folderByLower[it] })
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        val total = toUpload.size + toDownload.size
        if (total == 0) {
            _syncResult.value = SyncResult.Finished(0)
            return
        }

        var done = 0
        _syncProgress.value = done to total

        for (trip in toUpload) {
            val file = File(getTripsDir(), trip.fileName)
            if (file.exists()) {
                val ok = uploadCsv(settings, file)
                if (ok) {
                    tripDao.update(trip.copy(
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                } else {
                    tripDao.update(trip.copy(uploadStatus = 3))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        for (fileName in toDownload) {
            val destFile = File(getTripsDir(), fileName)
            if (downloadCsv(settings, fileName, destFile)) {
                val meta = parseCsvMeta(destFile)
                val existing = dbByLower[fileName.lowercase()]
                if (existing != null) {
                    tripDao.update(existing.copy(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        distanceKm = meta.distanceKm,
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                } else {
                    tripDao.insert(TripRecord(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        fileName = fileName,
                        distanceKm = meta.distanceKm,
                        uploadStatus = 2,
                        uploadedAt = System.currentTimeMillis()
                    ))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        _syncResult.value = SyncResult.Finished(total)
    }

    private fun getTripsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private data class CsvMeta(val startTime: Long, val endTime: Long, val distanceKm: Float)

    private fun parseCsvMeta(file: File): CsvMeta {
        var startTime = System.currentTimeMillis()
        var endTime = startTime
        var gpsDistanceKm = 0.0
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var minMileage = Float.MAX_VALUE
        var maxMileage = 0f
        try {
            val lines = file.readText().lines()
            if (lines.size < 2) return CsvMeta(startTime, endTime, 0f)
            val header = lines[0].lowercase().split(",").map { it.trim() }
            val latIdx = header.indexOfFirst { it == "latitude" }.takeIf { it >= 0 } ?: 6
            val lonIdx = header.indexOfFirst { it == "longitude" }.takeIf { it >= 0 } ?: 7
            val mileageIdx = header.indexOfFirst { it.contains("mileage") }
                .takeIf { it >= 0 } ?: 8
            val darkness = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            var first = true
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(",")
                if (parts.size < 2) continue
                val dateStr = parts[0].trim()
                val trimmed = if (dateStr.contains("T")) {
                    val t = dateStr.substringAfter("T")
                    dateStr.substringBefore("T") + "T" +
                            if (t.contains(".")) t.substringBefore(".") else t
                } else {
                    if (dateStr.count { it == '.' } > 2) dateStr.substringBeforeLast(".")
                    else dateStr
                }
                val parsed = try {
                    if (dateStr.contains("T")) iso.parse(trimmed) else darkness.parse(trimmed)
                } catch (_: Exception) { null }
                if (parsed != null) {
                    if (first) { startTime = parsed.time; first = false }
                    endTime = parsed.time
                }
                val lat = parts.getOrNull(latIdx)?.toDoubleOrNull()
                val lon = parts.getOrNull(lonIdx)?.toDoubleOrNull()
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    if (!lastLat.isNaN() && !lastLon.isNaN()) {
                        val d = haversineMeters(lastLat, lastLon, lat, lon)
                        if (d in 0.5..200.0) gpsDistanceKm += d / 1000.0
                    }
                    lastLat = lat
                    lastLon = lon
                }
                val mileage = parts.getOrNull(mileageIdx)?.toFloatOrNull()
                if (mileage != null && mileage > 0f) {
                    if (mileage < minMileage) minMileage = mileage
                    if (mileage > maxMileage) maxMileage = mileage
                }
            }
        } catch (_: Exception) {}
        val distance = when {
            gpsDistanceKm > 0.0 -> gpsDistanceKm.toFloat()
            minMileage != Float.MAX_VALUE && maxMileage > minMileage -> maxMileage - minMileage
            else -> 0f
        }
        return CsvMeta(startTime, endTime, distance)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * Persist read/write permission on the picked tree URI and save it.
     * Returns true if the folder is now usable.
     */
    suspend fun setSyncFolder(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(syncFolderUri = uri.toString()))
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not take persistable permission for $uri", e)
            false
        }
    }

    suspend fun clearSyncFolder() {
        val s = settingsRepository.get()
        s.syncFolderUri?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        settingsRepository.update(s.copy(syncFolderUri = null, lastSettingsBackupAt = null))
    }

    /** The chosen folder's DocumentFile, or null if none or no longer accessible. */
    fun getSyncFolder(settings: AppSettings): DocumentFile? {
        val uriStr = settings.syncFolderUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.takeIf { it.canWrite() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open sync folder", e)
            null
        }
    }

    fun getSyncFolderDisplayName(settings: AppSettings): String? {
        val folder = getSyncFolder(settings) ?: return null
        // Prefer the decoded tree path so the user sees where the folder lives
        // (e.g. "Internal storage › Documents › EUC Planet") instead of just the leaf.
        val treePath = folder.uri.lastPathSegment
        if (!treePath.isNullOrEmpty()) {
            val parts = treePath.split(":", limit = 2)
            val volume = when (parts.getOrNull(0)) {
                "primary" -> "Internal storage"
                null, "" -> null
                else -> parts[0]
            }
            val path = parts.getOrNull(1).orEmpty().trim('/')
            val segments = if (path.isEmpty()) emptyList() else path.split('/')
            val all = listOfNotNull(volume) + segments
            if (all.isNotEmpty()) return all.joinToString(" › ")
        }
        return folder.name
    }

    /** Serialise AppSettings to JSON and write to SETTINGS_BACKUP_NAME. */
    suspend fun backupSettings(): Boolean {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return false
        val json = settingsToJson(current).toString(2)
        return try {
            val existing = folder.findFile(SETTINGS_BACKUP_NAME)
            existing?.delete()
            val file = folder.createFile("application/json", SETTINGS_BACKUP_NAME) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return false
            settingsRepository.update(current.copy(lastSettingsBackupAt = System.currentTimeMillis()))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings backup failed", e)
            false
        }
    }

    /** Read settings.json from the folder and apply — keeps current syncFolder/device fields. */
    suspend fun restoreSettings(): Boolean {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return false
        val file = folder.findFile(SETTINGS_BACKUP_NAME) ?: return false
        return try {
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return false
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val restored = jsonToSettings(json, current)
            settingsRepository.update(restored)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings restore failed", e)
            false
        }
    }

    /** Enqueue the trip upload worker. */
    fun enqueueTripUpload(settings: AppSettings) {
        if (settings.syncFolderUri == null) return
        val request = OneTimeWorkRequestBuilder<TripUploadWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * List CSV filenames in the trips subfolder. Returns null if the folder
     * is unavailable, empty list if the folder exists but has no trips yet.
     */
    fun listFolderTripNames(settings: AppSettings): List<String>? {
        val root = getSyncFolder(settings) ?: return null
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return emptyList()
        return trips.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            if (doc.isFile && name.endsWith(".csv", ignoreCase = true)) name else null
        }
    }

    /** Copy a folder CSV into destFile. */
    fun downloadCsv(settings: AppSettings, fileName: String, destFile: File): Boolean {
        val root = getSyncFolder(settings) ?: return false
        val trips = root.findFile(TRIPS_SUBFOLDER) ?: return false
        val src = trips.findFile(fileName) ?: return false
        return try {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                destFile.outputStream().use { out -> input.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $fileName", e)
            false
        }
    }

    /**
     * Write a single CSV file to trips subfolder, returning true on success.
     * Called from the worker.
     */
    fun uploadCsv(settings: AppSettings, localFile: java.io.File): Boolean {
        val root = getSyncFolder(settings) ?: return false
        val tripsFolder = root.findFile(TRIPS_SUBFOLDER)
            ?: root.createDirectory(TRIPS_SUBFOLDER)
            ?: return false
        return try {
            tripsFolder.findFile(localFile.name)?.delete()
            val dest = tripsFolder.createFile("text/csv", localFile.name) ?: return false
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                localFile.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${localFile.name}", e)
            false
        }
    }

    // --- JSON mapping ------------------------------------------------------

    private fun settingsToJson(s: AppSettings): JSONObject = JSONObject().apply {
        // Everything except device bindings, sync folder itself, and last-backup timestamp.
        put("tiltbackSpeedKmh", s.tiltbackSpeedKmh)
        put("alarmSpeedKmh", s.alarmSpeedKmh)
        put("safetyTiltbackKmh", s.safetyTiltbackKmh)
        put("safetyAlarmKmh", s.safetyAlarmKmh)
        put("autoConnect", s.autoConnect)
        put("voiceEnabled", s.voiceEnabled)
        put("voicePeriodicEnabled", s.voicePeriodicEnabled)
        put("voiceOnlyWhenConnected", s.voiceOnlyWhenConnected)
        put("voiceIntervalSeconds", s.voiceIntervalSeconds)
        put("voiceSpeechRate", s.voiceSpeechRate)
        put("voiceLocale", s.voiceLocale)
        put("voiceAudioFocus", s.voiceAudioFocus)
        put("voiceOutputChannel", s.voiceOutputChannel)
        put("voiceReportSpeed", s.voiceReportSpeed)
        put("voiceReportBattery", s.voiceReportBattery)
        put("voiceReportTemp", s.voiceReportTemp)
        put("voiceReportPwm", s.voiceReportPwm)
        put("voiceReportDistance", s.voiceReportDistance)
        put("voiceReportTime", s.voiceReportTime)
        put("triggerReportSpeed", s.triggerReportSpeed)
        put("triggerReportBattery", s.triggerReportBattery)
        put("triggerReportTemp", s.triggerReportTemp)
        put("triggerReportPwm", s.triggerReportPwm)
        put("triggerReportDistance", s.triggerReportDistance)
        put("triggerReportTime", s.triggerReportTime)
        put("voiceReportRecording", s.voiceReportRecording)
        put("triggerReportRecording", s.triggerReportRecording)
        put("voiceReportOrder", s.voiceReportOrder)
        put("announceWheelLock", s.announceWheelLock)
        put("announceLights", s.announceLights)
        put("announceRecording", s.announceRecording)
        put("announceConnection", s.announceConnection)
        put("announceGps", s.announceGps)
        put("announceSafetyMode", s.announceSafetyMode)
        put("announceWelcome", s.announceWelcome)
        put("autoRecord", s.autoRecord)
        put("autoRecordStartInMotion", s.autoRecordStartInMotion)
        put("autoRecordStopIdleSeconds", s.autoRecordStopIdleSeconds)
        put("flic1Name", s.flic1Name)
        put("flic1Click", s.flic1Click)
        put("flic1DoubleClick", s.flic1DoubleClick)
        put("flic1Hold", s.flic1Hold)
        put("flic2Name", s.flic2Name)
        put("flic2Click", s.flic2Click)
        put("flic2DoubleClick", s.flic2DoubleClick)
        put("flic2Hold", s.flic2Hold)
        put("flic3Name", s.flic3Name)
        put("flic3Click", s.flic3Click)
        put("flic3DoubleClick", s.flic3DoubleClick)
        put("flic3Hold", s.flic3Hold)
        put("flic4Name", s.flic4Name)
        put("flic4Click", s.flic4Click)
        put("flic4DoubleClick", s.flic4DoubleClick)
        put("flic4Hold", s.flic4Hold)
        put("autoLightsEnabled", s.autoLightsEnabled)
        put("autoLightsOnMinutesBefore", s.autoLightsOnMinutesBefore)
        put("autoLightsOffMinutesAfter", s.autoLightsOffMinutesAfter)
        put("autoVolumeEnabled", s.autoVolumeEnabled)
        put("autoVolumeCurve", s.autoVolumeCurve)
        put("imperialUnits", s.imperialUnits)
        put("volumeKeysEnabled", s.volumeKeysEnabled)
        put("volumeUpClick", s.volumeUpClick)
        put("volumeUpHold", s.volumeUpHold)
        put("volumeDownClick", s.volumeDownClick)
        put("volumeDownHold", s.volumeDownHold)
        put("language", s.language)
        put("themeMode", s.themeMode)
        put("accentColor", s.accentColor)
        put("showGaugeColorBand", s.showGaugeColorBand)
        put("gaugeOrangeThresholdPct", s.gaugeOrangeThresholdPct)
        put("gaugeRedThresholdPct", s.gaugeRedThresholdPct)
        put("hapticFeedback", s.hapticFeedback)
        put("currentDisplayMode", s.currentDisplayMode)
    }

    private fun jsonToSettings(j: JSONObject, base: AppSettings): AppSettings = base.copy(
        tiltbackSpeedKmh = j.optDouble("tiltbackSpeedKmh", base.tiltbackSpeedKmh.toDouble()).toFloat(),
        alarmSpeedKmh = j.optDouble("alarmSpeedKmh", base.alarmSpeedKmh.toDouble()).toFloat(),
        safetyTiltbackKmh = j.optDouble("safetyTiltbackKmh", base.safetyTiltbackKmh.toDouble()).toFloat(),
        safetyAlarmKmh = j.optDouble("safetyAlarmKmh", base.safetyAlarmKmh.toDouble()).toFloat(),
        autoConnect = j.optBoolean("autoConnect", base.autoConnect),
        voiceEnabled = j.optBoolean("voiceEnabled", base.voiceEnabled),
        voicePeriodicEnabled = j.optBoolean("voicePeriodicEnabled", base.voicePeriodicEnabled),
        voiceOnlyWhenConnected = j.optBoolean("voiceOnlyWhenConnected", base.voiceOnlyWhenConnected),
        voiceIntervalSeconds = j.optInt("voiceIntervalSeconds", base.voiceIntervalSeconds),
        voiceSpeechRate = j.optDouble("voiceSpeechRate", base.voiceSpeechRate.toDouble()).toFloat(),
        voiceLocale = j.optString("voiceLocale", base.voiceLocale),
        voiceAudioFocus = j.optString("voiceAudioFocus", base.voiceAudioFocus),
        voiceOutputChannel = j.optString("voiceOutputChannel", base.voiceOutputChannel),
        voiceReportSpeed = j.optBoolean("voiceReportSpeed", base.voiceReportSpeed),
        voiceReportBattery = j.optBoolean("voiceReportBattery", base.voiceReportBattery),
        voiceReportTemp = j.optBoolean("voiceReportTemp", base.voiceReportTemp),
        voiceReportPwm = j.optBoolean("voiceReportPwm", base.voiceReportPwm),
        voiceReportDistance = j.optBoolean("voiceReportDistance", base.voiceReportDistance),
        voiceReportTime = j.optBoolean("voiceReportTime", base.voiceReportTime),
        triggerReportSpeed = j.optBoolean("triggerReportSpeed", base.triggerReportSpeed),
        triggerReportBattery = j.optBoolean("triggerReportBattery", base.triggerReportBattery),
        triggerReportTemp = j.optBoolean("triggerReportTemp", base.triggerReportTemp),
        triggerReportPwm = j.optBoolean("triggerReportPwm", base.triggerReportPwm),
        triggerReportDistance = j.optBoolean("triggerReportDistance", base.triggerReportDistance),
        triggerReportTime = j.optBoolean("triggerReportTime", base.triggerReportTime),
        voiceReportRecording = j.optBoolean("voiceReportRecording", base.voiceReportRecording),
        triggerReportRecording = j.optBoolean("triggerReportRecording", base.triggerReportRecording),
        voiceReportOrder = j.optString("voiceReportOrder", base.voiceReportOrder),
        announceWheelLock = j.optBoolean("announceWheelLock", base.announceWheelLock),
        announceLights = j.optBoolean("announceLights", base.announceLights),
        announceRecording = j.optBoolean("announceRecording", base.announceRecording),
        announceConnection = j.optBoolean("announceConnection", base.announceConnection),
        announceGps = j.optBoolean("announceGps", base.announceGps),
        announceSafetyMode = j.optBoolean("announceSafetyMode", base.announceSafetyMode),
        announceWelcome = j.optBoolean("announceWelcome", base.announceWelcome),
        autoRecord = j.optBoolean("autoRecord", base.autoRecord),
        autoRecordStartInMotion = j.optBoolean(
            "autoRecordStartInMotion",
            j.optBoolean("autoRecordOnlyInMotion", base.autoRecordStartInMotion)
        ),
        autoRecordStopIdleSeconds = j.optInt("autoRecordStopIdleSeconds", base.autoRecordStopIdleSeconds),
        flic1Name = j.optString("flic1Name", base.flic1Name),
        flic1Click = j.optString("flic1Click", base.flic1Click),
        flic1DoubleClick = j.optString("flic1DoubleClick", base.flic1DoubleClick),
        flic1Hold = j.optString("flic1Hold", base.flic1Hold),
        flic2Name = j.optString("flic2Name", base.flic2Name),
        flic2Click = j.optString("flic2Click", base.flic2Click),
        flic2DoubleClick = j.optString("flic2DoubleClick", base.flic2DoubleClick),
        flic2Hold = j.optString("flic2Hold", base.flic2Hold),
        flic3Name = j.optString("flic3Name", base.flic3Name),
        flic3Click = j.optString("flic3Click", base.flic3Click),
        flic3DoubleClick = j.optString("flic3DoubleClick", base.flic3DoubleClick),
        flic3Hold = j.optString("flic3Hold", base.flic3Hold),
        flic4Name = j.optString("flic4Name", base.flic4Name),
        flic4Click = j.optString("flic4Click", base.flic4Click),
        flic4DoubleClick = j.optString("flic4DoubleClick", base.flic4DoubleClick),
        flic4Hold = j.optString("flic4Hold", base.flic4Hold),
        autoLightsEnabled = j.optBoolean("autoLightsEnabled", base.autoLightsEnabled),
        autoLightsOnMinutesBefore = j.optInt("autoLightsOnMinutesBefore", base.autoLightsOnMinutesBefore),
        autoLightsOffMinutesAfter = j.optInt("autoLightsOffMinutesAfter", base.autoLightsOffMinutesAfter),
        autoVolumeEnabled = j.optBoolean("autoVolumeEnabled", base.autoVolumeEnabled),
        autoVolumeCurve = j.optString("autoVolumeCurve", base.autoVolumeCurve),
        imperialUnits = j.optBoolean("imperialUnits", base.imperialUnits),
        volumeKeysEnabled = j.optBoolean("volumeKeysEnabled", base.volumeKeysEnabled),
        volumeUpClick = j.optString("volumeUpClick", base.volumeUpClick),
        volumeUpHold = j.optString("volumeUpHold", base.volumeUpHold),
        volumeDownClick = j.optString("volumeDownClick", base.volumeDownClick),
        volumeDownHold = j.optString("volumeDownHold", base.volumeDownHold),
        language = j.optString("language", base.language),
        themeMode = j.optString("themeMode", base.themeMode),
        accentColor = j.optString("accentColor", base.accentColor),
        showGaugeColorBand = j.optBoolean("showGaugeColorBand", base.showGaugeColorBand),
        gaugeOrangeThresholdPct = j.optInt("gaugeOrangeThresholdPct", base.gaugeOrangeThresholdPct),
        gaugeRedThresholdPct = j.optInt("gaugeRedThresholdPct", base.gaugeRedThresholdPct),
        hapticFeedback = j.optBoolean("hapticFeedback", base.hapticFeedback),
        currentDisplayMode = j.optString("currentDisplayMode", base.currentDisplayMode)
    )
}
