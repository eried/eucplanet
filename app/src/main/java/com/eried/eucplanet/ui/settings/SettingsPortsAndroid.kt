package com.eried.eucplanet.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eried.eucplanet.amazfit.AmazfitBridge
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.audio.HornPlayer
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.data.repository.AppHealthRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.startLinkFlow
import com.eried.eucplanet.data.store.OverlayPresetJson
import com.eried.eucplanet.data.store.OverlayPresetStore
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.garmin.GarminBridge
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.service.hud.HudCommandSink
import com.eried.eucplanet.ui.dashboard.AndroidDashboardAutomation
import com.eried.eucplanet.ui.dashboard.AndroidDashboardSync
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import com.eried.eucplanet.wear.WearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// The Android side of SettingsPorts, over the classes SettingsViewModel used directly.

/** The Dropbox link flow needs an Activity for its Custom Tab. */
fun SettingsViewModel.linkDropbox(activityContext: Context) =
    dropboxRepository.startLinkFlow(activityContext)

class AndroidSettingsVoice(private val voice: VoiceService) : SettingsVoice {
    override val availableVoices = voice.availableVoices
    override val availableVoiceChoices = voice.availableVoiceChoices
    override fun currentVoiceLanguage() = voice.currentVoiceLanguage()
    override fun pickVoiceForLanguage(langCode: String) = voice.pickVoiceForLanguage(langCode)
    override fun previewReport(data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean) =
        voice.previewReport(data, settings, isRecording, periodic)
    override fun setVoiceLocale(localeTag: String) = voice.setVoiceLocale(localeTag)
    override fun setVoiceName(name: String) = voice.setVoiceName(name)
    override fun testSpeak(text: String, speechRate: Float, localeTag: String, voiceName: String) =
        voice.testSpeak(text, speechRate, localeTag, voiceName)
}

class AndroidPromptTones(private val tones: TonePlayer) : PromptTones {
    override suspend fun playPrompt() = tones.playPrompt()
    override suspend fun playErrorPrompt() = tones.playErrorPrompt()
}

class AndroidSettingsTrips(private val trips: TripRepository) : SettingsTrips {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val recording = trips.recording
    override val tripCount = trips.tripCount
    override val currentLocation = trips.currentLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.currentLocation.value?.toPhoneLocation())
    override suspend fun allTripFileNames() = trips.allTripFileNames()
    override suspend fun clearAll() = trips.clearAll()
}

class AndroidSettingsSync(private val sync: SyncManager) :
    SettingsSync, DashboardSync by AndroidDashboardSync(sync) {
    override val activeSyncKind = sync.activeSyncKind
    override val syncCancelling = sync.syncCancelling
    override val syncConflictFirstSettings = sync.syncConflictFirstSettings
    override val syncConflictKind = sync.syncConflictKind
    override val syncConflictPrompt = sync.syncConflictPrompt
    override val syncProgress = sync.syncProgress
    override val syncResult = sync.syncResult
    override suspend fun backupSettingsAs(name: String?, overwrite: Boolean) = sync.backupSettingsAs(name, overwrite)
    override fun cancelActiveSync() = sync.cancelActiveSync()
    override fun cancelDropboxSync() = sync.cancelDropboxSync()
    override fun cancelEucStatsUpload() = sync.cancelEucStatsUpload()
    override fun cancelSyncConflict() = sync.cancelSyncConflict()
    override suspend fun clearSyncFolder() = sync.clearSyncFolder()
    override fun consumeSyncResult() = sync.consumeSyncResult()
    override suspend fun deleteRiderIdFile() = sync.deleteRiderIdFile()
    override fun enqueueEucStatsUpload(settings: AppSettings) = sync.enqueueEucStatsUpload(settings)
    override fun enqueueTripUpload(settings: AppSettings) = sync.enqueueTripUpload(settings)
    override suspend fun ensureRiderIdFile(storeId: String) = sync.ensureRiderIdFile(storeId)
    override fun getSyncFolderDisplayName(settings: AppSettings) = sync.getSyncFolderDisplayName(settings)
    override fun listFolderTripNames(settings: AppSettings) = sync.listFolderTripNames(settings)
    override suspend fun resetAllEucstatsRows() = sync.resetAllEucstatsRows()
    override suspend fun resetUnfinishedEucstatsRows() = sync.resetUnfinishedEucstatsRows()
    override fun resolveSyncConflict(choice: com.eried.eucplanet.data.sync.SyncChoice) = sync.resolveSyncConflict(choice)
    override fun sanitizeBackupName(raw: String) = sync.sanitizeBackupName(raw)
    override fun startDropboxSync() = sync.startDropboxSync()
    override fun stopDropboxSync() = sync.stopDropboxSync()
}

class AndroidSettingsAutomation(private val automation: AutomationManager) :
    SettingsAutomation, DashboardAutomation by AndroidDashboardAutomation(automation) {
    override fun resetMediaControl() = automation.resetMediaControl()
    override fun resetProximityLock() = automation.resetProximityLock()
    override fun restoreBaselineVolume() = automation.restoreBaselineVolume()
}

class AndroidWatchLinks(
    private val wear: WearBridge,
    private val garmin: GarminBridge,
    amazfit: AmazfitBridge,
) : WatchLinks {
    override val wearNodes = wear.pairedNodes
    override val garminDevices = garmin.pairedDevices
    override val garminRateHz = garmin.deliveryRateHz
    override val garminLastSuccessAtMs = garmin.lastSuccessAtMs
    override val amazfitDevices = amazfit.pairedDevices
    override val amazfitRateHz = amazfit.deliveryRateHz
    override val amazfitLastSuccessAtMs = amazfit.lastSuccessAtMs

    // Wear OS gets the /euc/wake message, Garmin fires openApplication().
    override fun pingWatchesToWake() {
        wear.pingWatchToWake()
        garmin.pingWatchToWake()
    }
}

class AndroidEngineSoundPreview(private val engine: EngineSoundEngine) : EngineSoundPreview {
    override fun previewProfile(
        key: String, durationMs: Long, volume: Float?, muffler: String?, gearbox: String?, scenario: String,
    ) = engine.previewProfile(key, durationMs, volume, muffler, gearbox, scenario)
}

class AndroidHornSound(private val horn: HornPlayer) : HornSound {
    override val isReady get() = horn.isReady
    override fun import(uri: String) = horn.import(Uri.parse(uri))
    override fun play() = horn.play()
}

class AndroidSplitTimes(private val splits: AccelSplitRepository) : SplitTimes {
    override val session = splits.session
    override fun reset() = splits.reset()
}

class AndroidOnlineLeaderboard(private val stats: EucStatsRepository) : OnlineLeaderboard {
    override val card = stats.card
    override val cardLoaded = stats.cardLoaded
    override val cardMissing = stats.cardMissing
    override suspend fun refreshCard() { stats.refreshCard() }
    override suspend fun register(displayName: String, flag: String, avatarPngBase64: String) =
        stats.register(displayName, flag, avatarPngBase64)
    override suspend fun syncPendingNow(onProgress: (done: Int, total: Int) -> Unit) = stats.syncPendingNow(onProgress)
    override suspend fun fetchProfile() = stats.fetchProfile()
    override suspend fun editProfile(displayName: String?, flag: String?, avatarPngBase64: String?) =
        stats.editProfile(displayName, flag, avatarPngBase64)
    override suspend fun deleteAccount() = stats.deleteAccount()
    override suspend fun exportData() = stats.exportData()
}

class AndroidHudSettingsLink(
    sink: HudCommandSink,
    private val overlayPresetStore: OverlayPresetStore,
) : HudSettingsLink {
    override val hudVersion = sink.hudVersion
    override val hudEverConnected = sink.hudEverConnected

    // The portrait / landscape split uses the same rule as the Studio: more
    // than half of the elements rotated => landscape.
    override suspend fun overlayLists(): SettingsViewModel.HudOverlayLists {
        val hasFolder = overlayPresetStore.presetFolderAvailable()
        val saved = if (hasFolder) overlayPresetStore.listPresets() else emptyList()
        val portrait = mutableListOf<String>()
        val landscape = mutableListOf<String>()
        overlayPresetStore.listBundledPresets().forEach { name ->
            val els = overlayPresetStore.loadBundledPreset(name)?.elements
            if (els != null && els.isNotEmpty() &&
                els.count { it.rotationDeg != 0f } * 2 > els.size
            ) {
                landscape.add(name)
            } else {
                portrait.add(name)
            }
        }
        return SettingsViewModel.HudOverlayLists(hasFolder, saved, portrait, landscape)
    }

    override suspend fun overlayJson(name: String): String? {
        val preset = overlayPresetStore.loadBundledPreset(name)
            ?: overlayPresetStore.loadPreset(name)
            ?: return null
        return OverlayPresetJson.toJson(preset).toString()
    }
}

class AndroidSettingsPlatform(
    private val context: Context,
    private val health: AppHealthRepository,
) : SettingsPlatform {
    override fun pipAllowed() = health.pipAllowed()
    override fun openPipSettings() = health.openPipSettings()
    override fun notificationAccessAllowed() = health.notificationAccessAllowed()
    override fun openNotificationAccessSettings() = health.openNotificationAccessSettings()
    override fun refreshPermissionWarnings(
        pipRequested: Boolean, phoneHudRequested: Boolean, mediaRateRequested: Boolean,
    ) = health.refreshPermissionWarnings(pipRequested, phoneHudRequested, mediaRateRequested)

    override fun applyAppLanguage(tag: String) = com.eried.eucplanet.util.LocaleHelper.apply(tag)

    // Turning keep-alive OFF: ask the service to stand down if nothing else
    // needs it. (Turning it ON is handled by MainActivity's reactive service start.)
    override fun stopKeepAlive() {
        if (WheelService.isRunning) {
            val intent = Intent(context, WheelService::class.java)
                .apply { action = WheelService.ACTION_STOP_KEEPALIVE }
            try { context.startService(intent) } catch (_: Exception) {}
        }
    }
}
