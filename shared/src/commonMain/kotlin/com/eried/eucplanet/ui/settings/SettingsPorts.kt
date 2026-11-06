package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.audio.HornImportResult
import com.eried.eucplanet.data.eucstats.RegisterResult
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.sync.BackupOutcome
import com.eried.eucplanet.data.sync.RiderFileResult
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncConflictKind
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.service.AccelSplitTracker
import com.eried.eucplanet.service.VoiceChoice
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// What SettingsViewModel needs from the rest of the app, one small interface
// per collaborator with only the members it calls, like DashboardPorts. The
// Android classes implement them through thin adapters; iOS supplies its own
// or a no-op until the feature lands.

/** The spoken voice (VoiceService). */
interface SettingsVoice {
    val availableVoices: StateFlow<List<VoiceOption>>
    val availableVoiceChoices: StateFlow<List<VoiceChoice>>
    fun currentVoiceLanguage(): String
    /** A voice locale id for [langCode], in VoiceOption.localeId form, or null. */
    fun pickVoiceForLanguage(langCode: String): String?
    fun previewReport(data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean)
    fun setVoiceLocale(localeTag: String)
    fun setVoiceName(name: String)
    fun testSpeak(text: String, speechRate: Float, localeTag: String, voiceName: String = "")
}

/** The listening cues (TonePlayer). */
interface PromptTones {
    suspend fun playPrompt()
    suspend fun playErrorPrompt()
}

/** The trip recorder and phone location (TripRepository). */
interface SettingsTrips {
    val recording: StateFlow<Boolean>
    val tripCount: Flow<Int>
    val currentLocation: StateFlow<PhoneLocation?>
    suspend fun allTripFileNames(): List<String>
    suspend fun clearAll()
}

/** Backups, folder and Dropbox sync, and the leaderboard queue (SyncManager). */
interface SettingsSync : DashboardSync {
    val activeSyncKind: StateFlow<SyncConflictKind?>
    val syncCancelling: StateFlow<Boolean>
    val syncConflictFirstSettings: StateFlow<Boolean>
    val syncConflictKind: StateFlow<SyncConflictKind>
    val syncConflictPrompt: StateFlow<Int?>
    val syncProgress: StateFlow<Pair<Int, Int>?>
    val syncResult: StateFlow<SyncResult?>
    suspend fun backupSettingsAs(name: String?, overwrite: Boolean): BackupOutcome
    fun cancelActiveSync()
    fun cancelDropboxSync()
    fun cancelEucStatsUpload()
    fun cancelSyncConflict()
    suspend fun clearSyncFolder()
    fun consumeSyncResult()
    suspend fun deleteRiderIdFile(): Boolean
    fun enqueueEucStatsUpload(settings: AppSettings)
    fun enqueueTripUpload(settings: AppSettings)
    suspend fun ensureRiderIdFile(storeId: String): RiderFileResult
    fun getSyncFolderDisplayName(settings: AppSettings): String?
    fun listFolderTripNames(settings: AppSettings): List<String>?
    suspend fun resetAllEucstatsRows()
    suspend fun resetUnfinishedEucstatsRows()
    fun resolveSyncConflict(choice: SyncChoice)
    fun sanitizeBackupName(raw: String): String?
    fun startDropboxSync()
    fun stopDropboxSync()
}

/** The light, lock and media automations (AutomationManager). */
interface SettingsAutomation : DashboardAutomation {
    fun resetMediaControl()
    fun resetProximityLock()
    fun restoreBaselineVolume()
}

/** The paired watches (WearBridge, GarminBridge, AmazfitBridge); empty where there are none. */
interface WatchLinks {
    val wearNodes: StateFlow<List<String>>
    val garminDevices: StateFlow<List<String>>
    val garminRateHz: StateFlow<Double>
    val garminLastSuccessAtMs: StateFlow<Long>
    val amazfitDevices: StateFlow<List<String>>
    val amazfitRateHz: StateFlow<Double>
    val amazfitLastSuccessAtMs: StateFlow<Long>
    /** Wakes the watch app on every transport that can. */
    fun pingWatchesToWake()
}

/** The engine sound preview (EngineSoundEngine). */
interface EngineSoundPreview {
    fun previewProfile(
        key: String,
        durationMs: Long = 2500L,
        volume: Float? = null,
        muffler: String? = null,
        gearbox: String? = null,
        scenario: String = "DEFAULT",
    )
}

/** The rider's own horn clip (HornPlayer). */
interface HornSound {
    val isReady: Boolean
    /** [uri] is the picked file, as the platform spells it. Blocking. */
    fun import(uri: String): HornImportResult
    fun play(): Boolean
}

/** The acceleration split timer (AccelSplitRepository). */
interface SplitTimes {
    val session: StateFlow<AccelSplitTracker.Session>
    fun reset()
}

/** Legal Mode Lockdown, as the settings switch drives it (LegalLockdownController). */
interface LockdownSwitch {
    val armed: StateFlow<Boolean>
    fun isValidPin(pin: String): Boolean
    fun isEngaged(): Boolean
    suspend fun arm(pin: String, engageNow: Boolean): Boolean
    suspend fun disarmIfNotEngaged()
}

/** The online leaderboard account (EucStatsRepository). */
interface OnlineLeaderboard {
    val card: StateFlow<RiderCard?>
    val cardLoaded: StateFlow<Boolean>
    val cardMissing: StateFlow<Boolean>
    suspend fun refreshCard()
    suspend fun register(displayName: String, flag: String, avatarPngBase64: String): RegisterResult
    suspend fun syncPendingNow(onProgress: (done: Int, total: Int) -> Unit): com.eried.eucplanet.data.eucstats.SyncResult
    suspend fun fetchProfile(): RiderProfile?
    suspend fun editProfile(displayName: String? = null, flag: String? = null, avatarPngBase64: String? = null): Int
    suspend fun deleteAccount(): Boolean
    suspend fun exportData(): String?
}

/** The HUD companion's state, for the HUD rows; absent on phones without one. */
interface HudSettingsLink {
    val hudVersion: StateFlow<String?>
    val hudEverConnected: StateFlow<Boolean>
    suspend fun overlayLists(): SettingsViewModel.HudOverlayLists
    /** The preset's JSON, bundled first and then saved, or null when neither has it. */
    suspend fun overlayJson(name: String): String?
}

/** Things only the phone's own platform does for settings. */
interface SettingsPlatform {
    fun pipAllowed(): Boolean
    fun openPipSettings()
    fun notificationAccessAllowed(): Boolean
    fun openNotificationAccessSettings()
    fun refreshPermissionWarnings(
        pipRequested: Boolean = false,
        phoneHudRequested: Boolean = false,
        mediaRateRequested: Boolean = false,
    )
    /** Switches the app's own language to [tag]; blank is the default language. */
    fun applyAppLanguage(tag: String)
    /** Asks a running wheel service to drop its keep-alive. */
    fun stopKeepAlive()
}
