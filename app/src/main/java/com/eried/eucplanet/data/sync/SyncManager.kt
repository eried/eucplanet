package com.eried.eucplanet.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
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
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "SyncManager"
        const val SETTINGS_BACKUP_NAME = "eucplanet_settings.json"
        const val TRIPS_SUBFOLDER = "trips"
        const val UPLOAD_WORK_NAME = "trip_upload"
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
        return folder.name ?: folder.uri.lastPathSegment
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
        put("autoRecordOnlyInMotion", s.autoRecordOnlyInMotion)
        put("autoRecordStopWhenIdle", s.autoRecordStopWhenIdle)
        put("autoRecordStopIdleSeconds", s.autoRecordStopIdleSeconds)
        put("flic1Name", s.flic1Name)
        put("flic1Click", s.flic1Click)
        put("flic1DoubleClick", s.flic1DoubleClick)
        put("flic1Hold", s.flic1Hold)
        put("flic2Name", s.flic2Name)
        put("flic2Click", s.flic2Click)
        put("flic2DoubleClick", s.flic2DoubleClick)
        put("flic2Hold", s.flic2Hold)
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
    }

    private fun jsonToSettings(j: JSONObject, base: AppSettings): AppSettings = base.copy(
        tiltbackSpeedKmh = j.optDouble("tiltbackSpeedKmh", base.tiltbackSpeedKmh.toDouble()).toFloat(),
        alarmSpeedKmh = j.optDouble("alarmSpeedKmh", base.alarmSpeedKmh.toDouble()).toFloat(),
        safetyTiltbackKmh = j.optDouble("safetyTiltbackKmh", base.safetyTiltbackKmh.toDouble()).toFloat(),
        safetyAlarmKmh = j.optDouble("safetyAlarmKmh", base.safetyAlarmKmh.toDouble()).toFloat(),
        autoConnect = j.optBoolean("autoConnect", base.autoConnect),
        voiceEnabled = j.optBoolean("voiceEnabled", base.voiceEnabled),
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
        autoRecordOnlyInMotion = j.optBoolean("autoRecordOnlyInMotion", base.autoRecordOnlyInMotion),
        autoRecordStopWhenIdle = j.optBoolean("autoRecordStopWhenIdle", base.autoRecordStopWhenIdle),
        autoRecordStopIdleSeconds = j.optInt("autoRecordStopIdleSeconds", base.autoRecordStopIdleSeconds),
        flic1Name = j.optString("flic1Name", base.flic1Name),
        flic1Click = j.optString("flic1Click", base.flic1Click),
        flic1DoubleClick = j.optString("flic1DoubleClick", base.flic1DoubleClick),
        flic1Hold = j.optString("flic1Hold", base.flic1Hold),
        flic2Name = j.optString("flic2Name", base.flic2Name),
        flic2Click = j.optString("flic2Click", base.flic2Click),
        flic2DoubleClick = j.optString("flic2DoubleClick", base.flic2DoubleClick),
        flic2Hold = j.optString("flic2Hold", base.flic2Hold),
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
        accentColor = j.optString("accentColor", base.accentColor)
    )
}
