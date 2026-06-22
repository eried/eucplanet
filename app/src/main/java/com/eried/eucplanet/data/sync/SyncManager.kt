package com.eried.eucplanet.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.SettingsJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
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
 * `settings.json` backup are written through DocumentFile, no provider-specific
 * code, no OAuth, the cloud app handles the upload.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val alarmDao: AlarmDao,
    private val dropboxRepository: DropboxRepository
) {
    companion object {
        private const val TAG = "SyncManager"
        const val SETTINGS_BACKUP_NAME = "eucplanet_settings.json"
        const val SETTINGS_BACKUP_PREFIX = "eucplanet_settings-"
        const val SETTINGS_BACKUP_SUFFIX = ".json"
        // Plain-text file holding ONLY the eucstats online rider id (the
        // store_id UUID, nothing else) -- the recovery token for "found a
        // previous profile" after a reinstall / new device. Name/flag/stats all
        // come from the server, so the id is the entire identity.
        const val RIDER_BACKUP_NAME = "eucstats_riderid.txt"
        const val TRIPS_SUBFOLDER = "trips"
        const val UPLOAD_WORK_NAME = "trip_upload"
        const val EUCSTATS_UPLOAD_WORK_NAME = "eucstats_upload"
        const val DROPBOX_SYNC_WORK_NAME = "dropbox_sync"
        const val KEY_ATTEMPT = "attempt"

        // Custom backoff curve, since WorkManager's native MAX_BACKOFF_MILLIS is
        // a hard-coded 5h and the workers schedule their own next attempt rather
        // than returning Result.retry().
        //
        // attempt 0  → immediate (initial enqueue)
        // attempt 1  → 15s
        // attempt 2  → 30s
        // attempt 3  → 1m
        // attempt 4  → 2m
        // attempt 5  → 4m
        // attempt 6  → 8m
        // attempt 7  → 16m
        // attempt 8  → 32m
        // attempt 9+ → 1h (capped, retries forever)
        private const val BACKOFF_BASE_SECONDS = 15L
        private const val BACKOFF_MAX_SECONDS = 3600L

        fun delayForAttempt(attempt: Int): Long {
            if (attempt <= 0) return 0L
            val shift = (attempt - 1).coerceAtMost(20)
            val raw = BACKOFF_BASE_SECONDS * (1L shl shift)
            return raw.coerceAtMost(BACKOFF_MAX_SECONDS)
        }
    }

    // App-scoped so trip sync survives settings screen navigation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The current rider's store_id, read from the `.txt` recovery file in the
     * sync folder. This is the **single source of truth** for the rider's
     * online identity. We deliberately do NOT keep it in DataStore /
     * AppSettings, so the .txt and the server card together carry everything
     * a profile needs and nothing about the rider's name / flag / join date
     * ends up persisted on-device beyond what the server already holds.
     *
     * Null while the rider is unregistered, while no sync folder is
     * configured, or while the .txt is missing. Re-read on init and on any
     * `syncFolderUri` change so a fresh folder pick or an unlink updates
     * every consumer (trip upload, profile card, the restore prompt) in
     * lock-step.
     */
    private val _riderStoreId = MutableStateFlow<String?>(null)
    val riderStoreId: StateFlow<String?> = _riderStoreId.asStateFlow()

    init {
        scope.launch {
            _riderStoreId.value = readRiderIdFile()
            settingsRepository.settings
                .map { it.syncFolderUri }
                .distinctUntilChanged()
                .collect { _riderStoreId.value = readRiderIdFile() }
        }
    }

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning.asStateFlow()

    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    private val _syncConflictPrompt = MutableStateFlow<Int?>(null)
    val syncConflictPrompt: StateFlow<Int?> = _syncConflictPrompt.asStateFlow()

    /** Which sync the conflict dialog is for. The same dialog handles both
     *  the SAF folder sync and Dropbox sync; the only difference is the
     *  button labels (FOLDER → "Backup", DROPBOX → "Dropbox"). */
    private val _syncConflictKind = MutableStateFlow(SyncConflictKind.FOLDER)
    val syncConflictKind: StateFlow<SyncConflictKind> = _syncConflictKind.asStateFlow()

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
            _syncConflictKind.value = SyncConflictKind.FOLDER
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
            // Stream the CSV line-by-line instead of readText().lines(), so a
            // long trip (a big CSV) never has to sit fully in memory at once.
            file.bufferedReader().use { reader ->
                val headerLine = reader.readLine() ?: return CsvMeta(startTime, endTime, 0f)
                val header = headerLine.lowercase().split(",").map { it.trim() }
                val latIdx = header.indexOfFirst { it == "latitude" }.takeIf { it >= 0 } ?: 6
                val lonIdx = header.indexOfFirst { it == "longitude" }.takeIf { it >= 0 } ?: 7
                val mileageIdx = header.indexOfFirst { it.contains("mileage") }
                    .takeIf { it >= 0 } ?: 8
                val darkness = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                var first = true
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEachLine
                    val parts = line.split(",")
                    if (parts.size < 2) return@forEachLine
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
        settingsRepository.update(s.copy(
            syncFolderUri = null,
            lastSettingsBackupAt = null,
            onlineUploadEnabled = false,  // online upload requires a folder
        ))
        // Both destinations just lost their prerequisites: folder is gone, and
        // the rider id file went with it. Drop any pending retries so we don't
        // sit on a backed-off worker that would only no-op when it finally fires.
        cancelTripUpload()
        cancelEucStatsUpload()
        // Clear pending/failed eucstats icons; status=2 (already on the
        // leaderboard) is preserved so a rider who restores the same folder
        // doesn't lose their "uploaded" history.
        resetUnfinishedEucstatsRows()
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

    /** Serialise AppSettings + alarm rules to JSON and write to SETTINGS_BACKUP_NAME. */
    suspend fun backupSettings(): Boolean =
        backupSettingsAs(name = null, overwrite = true) == BackupOutcome.Saved

    /**
     * Write a named backup file in the sync folder. [name] = null is the
     * default `eucplanet_settings.json`; a non-null sanitised name produces
     * `eucplanet_settings-{name}.json`. When [overwrite] is false and the
     * target already exists, returns [BackupOutcome.AlreadyExists] without
     * touching the file so the caller can prompt the rider.
     */
    suspend fun backupSettingsAs(name: String?, overwrite: Boolean): BackupOutcome {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return BackupOutcome.Failed
        val fileName = buildBackupFileName(name)
        val existing = folder.findFile(fileName)
        if (existing != null && !overwrite) return BackupOutcome.AlreadyExists
        val payload = SettingsJson.toJson(SettingsJson.stripDeviceBindings(current)).apply {
            put("alarms", alarmsToJson(alarmDao.getAll()))
        }
        val json = payload.toString(2)
        return try {
            existing?.delete()
            val file = folder.createFile("application/json", fileName)
                ?: return BackupOutcome.Failed
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return BackupOutcome.Failed
            // Every successful backup updates the "last backup" label so the
            // rider sees "Last backup: <date> AS <name>" right after a named
            // save. Name is null for the default snapshot.
            settingsRepository.update(
                current.copy(
                    lastSettingsBackupAt = System.currentTimeMillis(),
                    lastSettingsBackupName = name
                )
            )
            BackupOutcome.Saved
        } catch (e: Exception) {
            Log.e(TAG, "Settings backup failed", e)
            BackupOutcome.Failed
        }
    }

    /**
     * Write a rider's store_id to the recovery file ([RIDER_BACKUP_NAME]) in
     * the sync folder and publish it on [riderStoreId]. This is the
     * registration / restore path's persistence step; we don't keep the id
     * anywhere else on-device. Name / flag / avatar / stats all live on the
     * server. Returns true on success; false if there's no folder or the
     * write failed.
     */
    suspend fun writeRiderId(storeId: String): Boolean {
        val folder = getSyncFolder(settingsRepository.get()) ?: return false
        val ok = try {
            folder.findFile(RIDER_BACKUP_NAME)?.delete()
            val file = folder.createFile("text/plain", RIDER_BACKUP_NAME)
                ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(storeId.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rider id write failed", e)
            false
        }
        if (ok) _riderStoreId.value = storeId
        return ok
    }

    /** Delete the recovery file ([RIDER_BACKUP_NAME]). Used when the rider
     *  deletes their account so the just-deleted profile is NOT offered for
     *  "restore" on the next Join (the store_id no longer exists server-side).
     *  Also clears [riderStoreId] so every consumer sees the unregistered
     *  state immediately. Best-effort; returns true if a file was deleted. */
    suspend fun deleteRiderIdFile(): Boolean {
        val folder = getSyncFolder(settingsRepository.get()) ?: run {
            _riderStoreId.value = null
            return false
        }
        val deleted = folder.findFile(RIDER_BACKUP_NAME)?.delete() ?: false
        _riderStoreId.value = null
        return deleted
    }

    /** Read the plain-text online rider id (store_id) from RIDER_BACKUP_NAME, or null. */
    suspend fun readRiderIdFile(): String? {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return null
        val file = folder.findFile(RIDER_BACKUP_NAME) ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.use {
                String(it.readBytes(), Charsets.UTF_8).trim()
            }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read rider id file", e)
            null
        }
    }

    /** Outcome of [ensureRiderIdFile]. */
    enum class RiderFileResult {
        /** No folder or no registered rider — nothing to do. */
        SKIPPED,
        /** The file already holds this rider's id. */
        ALREADY_PRESENT,
        /** The file was absent and has now been written with this rider's id. */
        WROTE,
        /** A DIFFERENT rider's id is in the file; left untouched so the caller can warn. */
        MISMATCH,
    }

    /**
     * Ensure the recovery file holds [storeId]. Used at registration / link
     * time so the rider's identity is hardened in the sync folder before
     * anything else can clobber it. Leaves a foreign rider's file untouched
     * and reports [RiderFileResult.MISMATCH] so the caller can warn.
     */
    suspend fun ensureRiderIdFile(storeId: String): RiderFileResult {
        getSyncFolder(settingsRepository.get()) ?: return RiderFileResult.SKIPPED
        return when (readRiderIdFile()) {
            storeId -> RiderFileResult.ALREADY_PRESENT
            null -> if (writeRiderId(storeId)) RiderFileResult.WROTE else RiderFileResult.SKIPPED
            else -> RiderFileResult.MISMATCH
        }
    }

    /** Read settings.json from the folder and apply, keeps current syncFolder/device fields. */
    suspend fun restoreSettings(): Boolean = restoreSettingsFrom(SETTINGS_BACKUP_NAME)

    /** Restore from the named backup file in the sync folder. */
    suspend fun restoreSettingsFrom(fileName: String): Boolean {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return false
        val file = folder.findFile(fileName) ?: return false
        return try {
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return false
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val restored = SettingsJson.fromJson(json, current)
            settingsRepository.update(restored)
            // Replace alarm rules wholesale only if the backup contains an
            // "alarms" array. Older backups (pre-v0.4.3) keep the user's
            // current rules untouched.
            if (json.has("alarms")) {
                val rules = jsonToAlarms(json.optJSONArray("alarms"))
                alarmDao.deleteAll()
                rules.forEach { alarmDao.insert(it.copy(id = 0)) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings restore failed", e)
            false
        }
    }

    /**
     * Reset rider configuration to factory defaults. Reuses the file-restore
     * merge with an in-memory [AppSettings] snapshot instead of a backup file:
     * [SettingsJson.stripDeviceBindings] drops the device + sync fields from the
     * factory JSON, so [SettingsJson.fromJson] keeps the rider's current
     * pairings, sync folder and backup history while every other field reverts
     * to its default. Custom alarm rules are cleared (a fresh install ships
     * none). Needs no sync folder — it's a purely local reset.
     */
    suspend fun restoreFactoryDefaults(): Boolean {
        val current = settingsRepository.get()
        return try {
            val factoryJson = SettingsJson.toJson(SettingsJson.stripDeviceBindings(AppSettings()))
            settingsRepository.update(SettingsJson.fromJson(factoryJson, current))
            alarmDao.deleteAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed", e)
            false
        }
    }

    /**
     * List every settings backup in the sync folder. The default
     * `eucplanet_settings.json` is always returned first (with [BackupEntry.label]
     * = null), followed by named snapshots sorted by display label.
     */
    suspend fun listSettingsBackups(): List<BackupEntry> {
        val current = settingsRepository.get()
        val folder = getSyncFolder(current) ?: return emptyList()
        val out = mutableListOf<BackupEntry>()
        val named = mutableListOf<BackupEntry>()
        folder.listFiles().forEach { doc ->
            val n = doc.name ?: return@forEach
            if (!n.endsWith(".json", ignoreCase = true)) return@forEach
            when {
                n.equals(SETTINGS_BACKUP_NAME, ignoreCase = true) -> {
                    out += BackupEntry(fileName = n, label = null)
                }
                n.startsWith(SETTINGS_BACKUP_PREFIX, ignoreCase = true) &&
                    n.length > SETTINGS_BACKUP_PREFIX.length + SETTINGS_BACKUP_SUFFIX.length -> {
                    val label = n.substring(
                        SETTINGS_BACKUP_PREFIX.length,
                        n.length - SETTINGS_BACKUP_SUFFIX.length
                    )
                    if (label.isNotEmpty()) named += BackupEntry(fileName = n, label = label)
                }
            }
        }
        named.sortBy { it.label?.lowercase() }
        return out + named
    }

    /**
     * The recoverable rider identity for this sync folder. The store_id in
     * `eucstats_riderid.txt` is the only thing the app needs to identify the
     * rider on reinstall. The display name and the rest of the profile come
     * from `api.getCard(storeId)` once the rider opts in to restore. Returns
     * null when no folder is configured, no `.txt` file is present, or the
     * file is empty.
     */
    suspend fun findRestorableRider(): RestorableRider? {
        val id = readRiderIdFile() ?: return null
        return RestorableRider(fileName = RIDER_BACKUP_NAME, storeId = id)
    }

    /**
     * Save a timestamped safety copy of the CURRENT settings before a restore
     * that would replace the rider identity, so the previous rider stays
     * recoverable even if the rider taps through the confirm. Best-effort.
     */
    suspend fun snapshotBeforeRestore(): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return backupSettingsAs(name = "before-restore-$stamp", overwrite = false) == BackupOutcome.Saved
    }

    /** Path-safe sanitiser. Strips anything that isn't [A-Za-z0-9_- ], trims,
     *  collapses whitespace, caps at 32 chars. Empty input returns null so the
     *  caller can show a validation error. */
    fun sanitizeBackupName(raw: String): String? {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(32)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun buildBackupFileName(name: String?): String =
        if (name == null) SETTINGS_BACKUP_NAME else "$SETTINGS_BACKUP_PREFIX$name$SETTINGS_BACKUP_SUFFIX"

    /** Initial enqueue of the folder-backup worker. Retries are scheduled by the
     *  worker itself via [scheduleTripUploadAttempt]. */
    fun enqueueTripUpload(settings: AppSettings) {
        if (settings.syncFolderUri == null) return
        scheduleTripUploadAttempt(attempt = 0)
    }

    /** Initial enqueue of the eucstats worker. Retries are scheduled by the worker
     *  itself via [scheduleEucStatsUploadAttempt]. */
    fun enqueueEucStatsUpload(settings: AppSettings) {
        scheduleEucStatsUploadAttempt(attempt = 0)
    }

    /** Schedule the folder worker for [attempt]. attempt=0 fires immediately;
     *  higher attempts use the [delayForAttempt] curve. Always under the same
     *  unique-work name so a fresh enqueue (new trip, manual retry) cancels and
     *  resets the retry chain. */
    fun scheduleTripUploadAttempt(attempt: Int) {
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<TripUploadWorker>()
            .setInputData(data)
            .setInitialDelay(delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Cancel any queued / in-backoff folder-upload work. Used when the rider
     *  unlinks the sync folder so a long-tail retry doesn't sit waiting for a
     *  destination that no longer exists. */
    fun cancelTripUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(UPLOAD_WORK_NAME)
    }

    /** Cancel any queued / in-backoff eucstats-upload work. Used when the rider
     *  toggles leaderboards off, deletes their account, or unlinks the sync
     *  folder (since the rider id lives in that folder). Without this, a retry
     *  parked on the 32m / 1h step would still fire later and find the
     *  prerequisites gone; cancelling here keeps the trip icons and WorkManager
     *  state in lockstep. */
    fun cancelEucStatsUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(EUCSTATS_UPLOAD_WORK_NAME)
    }

    /** Initial enqueue of the Dropbox sync worker. Retries reschedule
     *  themselves via [scheduleDropboxSyncAttempt]. Caller should check
     *  the linked state before calling — this is unconditional. */
    fun enqueueDropboxSync() {
        scheduleDropboxSyncAttempt(0)
    }

    /**
     * Foreground "Sync all" for Dropbox. Mirrors [startSync] but talks to
     * Dropbox: compare local trips dir against /trips/, prompt the rider
     * on shared file names with the same conflict dialog the SAF sync uses,
     * then upload / download to reconcile. Runs in the app-scoped coroutine
     * so leaving Settings does NOT cancel a half-finished reconcile.
     */
    fun startDropboxSync() {
        if (!_syncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                runDropboxSync()
            } finally {
                _syncProgress.value = null
                _syncConflictPrompt.value = null
                conflictChoice = null
                _syncRunning.value = false
            }
        }
    }

    private suspend fun runDropboxSync() {
        val settings = settingsRepository.get()
        if (settings.dropboxAccessToken.isBlank()) {
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val remote = dropboxRepository.listFolder("/trips")
        if (remote == null) {
            // Auth or network failed: same UX as "no folder" since the rider
            // can't act on the sync until they re-link or reconnect.
            _syncResult.value = SyncResult.NoFolder
            return
        }
        val tripsDir = getTripsDir()
        val localFiles = tripsDir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.toList().orEmpty()
        val localByLower = localFiles.associateBy { it.name.lowercase() }
        val remoteByLower = remote.keys.associateBy { it.lowercase() }

        val conflictKeys = remoteByLower.keys intersect localByLower.keys
        val remoteOnly = remoteByLower.keys - localByLower.keys
        val localOnly = localByLower.keys - remoteByLower.keys

        var choice = SyncChoice.IGNORE
        if (conflictKeys.isNotEmpty()) {
            val deferred = CompletableDeferred<SyncChoice>()
            conflictChoice = deferred
            _syncConflictKind.value = SyncConflictKind.DROPBOX
            _syncConflictPrompt.value = conflictKeys.size
            choice = deferred.await()
            _syncConflictPrompt.value = null
            conflictChoice = null
            if (choice == SyncChoice.CANCEL) return
        }

        val toUpload = mutableListOf<java.io.File>()
        val toDownload = mutableListOf<String>()
        localOnly.forEach { key -> localByLower[key]?.let { toUpload += it } }
        remoteOnly.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
        when (choice) {
            SyncChoice.APP -> conflictKeys.forEach { key -> localByLower[key]?.let { toUpload += it } }
            SyncChoice.FOLDER -> conflictKeys.forEach { key -> remoteByLower[key]?.let { toDownload += it } }
            SyncChoice.IGNORE, SyncChoice.CANCEL -> {}
        }

        val total = toUpload.size + toDownload.size
        if (total == 0) {
            _syncResult.value = SyncResult.Finished(0)
            return
        }

        var done = 0
        _syncProgress.value = done to total

        for (file in toUpload) {
            dropboxRepository.uploadFile("/trips/${file.name}", file.readBytes())
            done++
            _syncProgress.value = done to total
        }

        for (name in toDownload) {
            val bytes = dropboxRepository.downloadFile("/trips/$name")
            if (bytes != null) {
                val dest = File(tripsDir, name)
                dest.outputStream().use { it.write(bytes) }
                // Mirror the SAF path: if the file is not yet known to Room,
                // insert a row so it shows up in the trips list.
                val existing = tripDao.findByFileName(name)
                if (existing == null) {
                    val meta = parseCsvMeta(dest)
                    tripDao.insert(TripRecord(
                        startTime = meta.startTime,
                        endTime = meta.endTime,
                        fileName = name,
                        distanceKm = meta.distanceKm,
                        uploadStatus = 0,
                    ))
                }
            }
            done++
            _syncProgress.value = done to total
        }

        settingsRepository.update {
            it.copy(dropboxLastSyncAt = System.currentTimeMillis())
        }
        _syncResult.value = SyncResult.Finished(total)
    }

    fun scheduleDropboxSyncAttempt(attempt: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setInitialDelay(delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DROPBOX_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelDropboxSync() {
        WorkManager.getInstance(context).cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)
    }

    /** Clear pending / failed eucstats statuses for every trip. Used by the
     *  toggle-off and folder-unlink paths so the orange / red cloud icons stop
     *  appearing for trips that no longer have a path to upload. Status=2
     *  trips (already on the leaderboard) are preserved. */
    suspend fun resetUnfinishedEucstatsRows() {
        tripDao.resetUnfinishedEucstatsStatuses()
    }

    /** Clear ALL non-zero eucstats statuses, including the green ticks for
     *  previously-uploaded trips. Used only by the account-delete path: the
     *  server has nothing for this rider anymore so even status=2 is
     *  misleading. */
    suspend fun resetAllEucstatsRows() {
        tripDao.resetAllEucstatsStatuses()
    }

    /** Schedule the eucstats worker for [attempt]. See [scheduleTripUploadAttempt]
     *  for semantics; this one also carries the CONNECTED network constraint. */
    fun scheduleEucStatsUploadAttempt(attempt: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(KEY_ATTEMPT to attempt)
        val request = OneTimeWorkRequestBuilder<EucStatsUploadWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setInitialDelay(delayForAttempt(attempt), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EUCSTATS_UPLOAD_WORK_NAME,
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


    private fun alarmsToJson(rules: List<AlarmRule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("name", r.name)
                put("enabled", r.enabled)
                put("sortOrder", r.sortOrder)
                put("metric", r.metric)
                put("comparator", r.comparator)
                put("threshold", r.threshold.toDouble())
                put("beepEnabled", r.beepEnabled)
                put("beepFrequency", r.beepFrequency)
                put("beepDurationMs", r.beepDurationMs)
                put("beepCount", r.beepCount)
                put("voiceEnabled", r.voiceEnabled)
                put("voiceText", r.voiceText)
                put("vibrateEnabled", r.vibrateEnabled)
                put("vibrateDurationMs", r.vibrateDurationMs)
                put("vibrateTarget", r.vibrateTarget)
                put("cooldownSeconds", r.cooldownSeconds)
                put("repeatWhileActive", r.repeatWhileActive)
            })
        }
    }

    private fun jsonToAlarms(arr: JSONArray?): List<AlarmRule> {
        if (arr == null) return emptyList()
        val out = mutableListOf<AlarmRule>()
        val default = AlarmRule()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AlarmRule(
                name = o.optString("name", default.name),
                enabled = o.optBoolean("enabled", default.enabled),
                sortOrder = o.optInt("sortOrder", default.sortOrder),
                metric = o.optString("metric", default.metric),
                comparator = o.optString("comparator", default.comparator),
                threshold = o.optDouble("threshold", default.threshold.toDouble()).toFloat(),
                beepEnabled = o.optBoolean("beepEnabled", default.beepEnabled),
                beepFrequency = o.optInt("beepFrequency", default.beepFrequency),
                beepDurationMs = o.optInt("beepDurationMs", default.beepDurationMs),
                beepCount = o.optInt("beepCount", default.beepCount),
                voiceEnabled = o.optBoolean("voiceEnabled", default.voiceEnabled),
                voiceText = o.optString("voiceText", default.voiceText),
                vibrateEnabled = o.optBoolean("vibrateEnabled", default.vibrateEnabled),
                vibrateDurationMs = o.optInt("vibrateDurationMs", default.vibrateDurationMs),
                vibrateTarget = o.optString("vibrateTarget", default.vibrateTarget),
                cooldownSeconds = o.optInt("cooldownSeconds", default.cooldownSeconds),
                repeatWhileActive = o.optBoolean("repeatWhileActive", default.repeatWhileActive)
            )
        }
        return out
    }
}

/**
 * Result of a settings-backup attempt. [AlreadyExists] is only ever returned
 * when the caller asked not to overwrite.
 */
sealed interface BackupOutcome {
    data object Saved : BackupOutcome
    data object AlreadyExists : BackupOutcome
    data object Failed : BackupOutcome
}

/**
 * One row in the restore picker. [label] = null is the default backup
 * (`eucplanet_settings.json`); non-null is the rider-supplied snapshot name.
 */
data class BackupEntry(val fileName: String, val label: String?, val isFactory: Boolean = false)

/**
 * The rider identity carried by a settings backup, read without applying it.
 * Used to offer "restore your existing rider" when a sync folder is linked.
 */
data class RestorableRider(val fileName: String, val storeId: String)
