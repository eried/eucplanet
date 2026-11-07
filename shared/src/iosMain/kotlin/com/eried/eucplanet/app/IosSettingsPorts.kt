package com.eried.eucplanet.app

import com.eried.eucplanet.audio.HornImportResult
import com.eried.eucplanet.data.eucstats.RegisterResult
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.sync.BackupOutcome
import com.eried.eucplanet.data.sync.RiderFileResult
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncConflictKind
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.service.AccelSplitTracker
import com.eried.eucplanet.service.ToneSynth
import com.eried.eucplanet.service.VoiceChoice
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.settings.EngineSoundPreview
import com.eried.eucplanet.ui.settings.HornSound
import com.eried.eucplanet.ui.settings.HudSettingsLink
import com.eried.eucplanet.ui.settings.LockdownSwitch
import com.eried.eucplanet.ui.settings.OnlineLeaderboard
import com.eried.eucplanet.ui.settings.PromptTones
import com.eried.eucplanet.ui.settings.SettingsAutomation
import com.eried.eucplanet.ui.settings.SettingsPlatform
import com.eried.eucplanet.ui.settings.SettingsSync
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.settings.SettingsVoice
import com.eried.eucplanet.ui.settings.SplitTimes
import com.eried.eucplanet.ui.settings.WatchLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.NSUserDefaults

// The iOS side of SettingsPorts. What the iPhone has (voices, cues, Dropbox
// sync) is real; what it does not have yet, or ever (watches, the HUD, the
// folder backups), is an empty stand-in, and the iOS
// SettingsHost leaves its rows out. The leaderboard is real (IosEucStats.kt).
// See docs/IOS_PARITY.md.

/** The system voices, grouped by language as Android's TTS engine lists them. */
internal class IosSettingsVoice(private val voice: IosVoice) : SettingsVoice {
    private val voices: List<AVSpeechSynthesisVoice> by lazy {
        AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
    }

    /** "en-US" as Android's Locale.toString() writes it: "en_US". */
    private fun localeId(language: String) = language.replace('-', '_')

    // Each language in its own language, "Español (España)", as on Android.
    private fun nativeName(language: String): String {
        val locale = NSLocale(localeIdentifier = language)
        return locale.displayNameForKey(NSLocaleIdentifier, language) ?: language
    }

    override val availableVoices: StateFlow<List<VoiceOption>> = MutableStateFlow(
        voices.map { it.language }.distinct()
            .map { VoiceOption(localeId(it), nativeName(it)) }
            .sortedBy { it.displayName }
    ).asStateFlow()

    // Android's Voice.getQuality scale: 300 normal, 400 high, 500 very high.
    override val availableVoiceChoices: StateFlow<List<VoiceChoice>> = MutableStateFlow(
        voices.groupBy { it.language }.flatMap { (language, group) ->
            group.mapIndexed { i, v ->
                VoiceChoice(
                    name = v.identifier,
                    localeTag = localeId(language),
                    index = i + 1,
                    networkRequired = false,
                    quality = when (v.quality) {
                        AVSpeechSynthesisVoiceQualityPremium -> 500
                        AVSpeechSynthesisVoiceQualityEnhanced -> 400
                        else -> 300
                    },
                )
            }
        }
    ).asStateFlow()

    override fun currentVoiceLanguage(): String =
        (AVSpeechSynthesisVoice.currentLanguageCode()).substringBefore('-')

    override fun pickVoiceForLanguage(langCode: String): String? {
        val primary = langCode.substringBefore('-').substringBefore('_').lowercase()
        val acceptable = if (primary == "no") setOf("no", "nb", "nn") else setOf(primary)
        return voices.map { it.language }.distinct()
            .firstOrNull { it.substringBefore('-').lowercase() in acceptable }
            ?.let { localeId(it) }
    }

    override fun previewReport(data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean) =
        voice.previewReport(data, settings, isRecording, periodic)

    // The voice follows the settings on every utterance; nothing to hold here.
    override fun setVoiceLocale(localeTag: String) = Unit
    override fun setVoiceName(name: String) = Unit

    override fun testSpeak(text: String, speechRate: Float, localeTag: String, voiceName: String) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * speechRate).coerceIn(0.1f, 1f)
        val picked = voiceName.takeIf { it.isNotBlank() }?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
            ?: localeTag.takeIf { it.isNotBlank() }?.let { AVSpeechSynthesisVoice.voiceWithLanguage(it.replace('_', '-')) }
        picked?.let { utterance.voice = it }
        voice.speakNow(utterance)
    }
}

/** The listening cues, the same notes TonePlayer plays on Android. */
internal object IosPromptTones : PromptTones {
    override suspend fun playPrompt() = play(listOf(780 to 70, 1040 to 80), gapMs = 45, leadPadMs = 160, volumePct = 100)
    override suspend fun playErrorPrompt() = play(listOf(420 to 110, 310 to 150), gapMs = 30, leadPadMs = 160, volumePct = 85)

    private suspend fun play(notes: List<Pair<Int, Int>>, gapMs: Int, leadPadMs: Int, volumePct: Int) {
        val r = ToneSynth.renderNotes(notes, gapMs, leadPadMs, volumePct, minFrames = 0) ?: return
        IosTonePlayer.play(r)
    }
}

/**
 * Dropbox sync through [IosDropboxSync], with its conflict question surfaced
 * the way SyncManager surfaces it. No backup folder on iOS: the folder rows
 * report no folder.
 */
internal object IosSettingsSync : SettingsSync, DashboardSync by NoFolderSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val activeSyncKind: StateFlow<SyncConflictKind?> = IosDropboxSync.running
        .map { if (it) SyncConflictKind.DROPBOX else null }
        .stateIn(scope, SharingStarted.Eagerly, null)
    override val syncRunning: StateFlow<Boolean> = IosDropboxSync.running
    override val syncCancelling: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val syncConflictFirstSettings: StateFlow<Boolean> = IosDropboxSync.firstSettings
    override val syncConflictKind: StateFlow<SyncConflictKind> =
        MutableStateFlow(SyncConflictKind.DROPBOX).asStateFlow()
    override val syncConflictPrompt: StateFlow<Int?> = IosDropboxSync.conflict
        .map { if (it) 1 else null }
        .stateIn(scope, SharingStarted.Eagerly, null)
    override val syncProgress: StateFlow<Pair<Int, Int>?> = MutableStateFlow<Pair<Int, Int>?>(null).asStateFlow()
    override val syncResult: StateFlow<SyncResult?> = MutableStateFlow<SyncResult?>(null).asStateFlow()

    override suspend fun backupSettingsAs(name: String?, overwrite: Boolean) = BackupOutcome.Failed
    override fun cancelActiveSync() = IosDropboxSync.stop()
    override fun cancelDropboxSync() = IosDropboxSync.stop()
    override fun cancelEucStatsUpload() = IosEucStats.cancel()
    override fun cancelSyncConflict() = IosDropboxSync.resolve(SyncChoice.CANCEL)
    override suspend fun clearSyncFolder() = Unit
    override fun consumeSyncResult() = Unit
    override suspend fun deleteRiderIdFile() = IosEucStats.deleteRiderId()
    override fun enqueueEucStatsUpload(settings: AppSettings) = IosEucStats.sweep()
    override fun enqueueTripUpload(settings: AppSettings) = Unit
    // The rider id is a file in the app's own Documents, so it is the one this phone has.
    override suspend fun ensureRiderIdFile(storeId: String) = when (IosEucStats.readRiderId()) {
        storeId -> RiderFileResult.ALREADY_PRESENT
        null -> if (IosEucStats.writeRiderId(storeId)) RiderFileResult.WROTE else RiderFileResult.SKIPPED
        else -> RiderFileResult.MISMATCH
    }
    override val riderIdNeedsFolder = false
    override val riderStoreId: StateFlow<String?> get() = IosEucStats.riderStoreId
    override suspend fun writeRiderId(storeId: String) = IosEucStats.writeRiderId(storeId)
    override fun getSyncFolderDisplayName(settings: AppSettings): String? = null
    override fun listFolderTripNames(settings: AppSettings): List<String>? = null
    override suspend fun resetAllEucstatsRows() = IosAppGraph.database.tripDao().resetAllEucstatsStatuses()
    override suspend fun resetUnfinishedEucstatsRows() = IosAppGraph.database.tripDao().resetUnfinishedEucstatsStatuses()
    override fun resolveSyncConflict(choice: SyncChoice) = IosDropboxSync.resolve(choice)
    override fun sanitizeBackupName(raw: String): String? = null
    override fun startDropboxSync() = IosDropboxSync.syncNow()
    override fun stopDropboxSync() = IosDropboxSync.stop()
}

internal object NoSettingsAutomation : SettingsAutomation, DashboardAutomation by NoAutomation {
    override fun resetMediaControl() = Unit
    override fun resetProximityLock() = Unit
    override fun restoreBaselineVolume() = Unit
}

/** No watches on iOS. */
internal object NoWatches : WatchLinks {
    override val wearNodes: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
    override val garminDevices: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
    override val garminRateHz: StateFlow<Double> = MutableStateFlow(0.0).asStateFlow()
    override val garminLastSuccessAtMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    override val amazfitDevices: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
    override val amazfitRateHz: StateFlow<Double> = MutableStateFlow(0.0).asStateFlow()
    override val amazfitLastSuccessAtMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    override fun pingWatchesToWake() = Unit
}

/** The engine sound is not on iOS yet. */
internal object NoEngineSound : EngineSoundPreview {
    override fun previewProfile(
        key: String, durationMs: Long, volume: Float?, muffler: String?, gearbox: String?, scenario: String,
    ) = Unit
}

/** The custom horn clip is not on iOS yet. */
internal object NoHornSound : HornSound {
    override val isReady = false
    override fun import(uri: String) = HornImportResult.UNREADABLE
    override fun play() = false
}

/** The split timer is not on iOS yet. */
internal object NoSplitTimes : SplitTimes {
    override val session: StateFlow<AccelSplitTracker.Session> =
        MutableStateFlow(AccelSplitTracker.Session()).asStateFlow()
    override fun reset() = Unit
}

/** Legal Mode Lockdown is not on iOS yet: never armed, arming refused. */
internal object NoLockdownSwitch : LockdownSwitch {
    override val armed: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun isValidPin(pin: String) = Regex("^[0-9]{4,8}$").matches(pin)
    override fun isEngaged() = false
    override suspend fun arm(pin: String, engageNow: Boolean) = false
    override suspend fun disarmIfNotEngaged() = Unit
}

/** The HUD companion is an Android device; the iPhone does not talk to it. */
internal object NoHud : HudSettingsLink {
    override val hudVersion: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val hudEverConnected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override suspend fun overlayLists() = SettingsViewModel.HudOverlayLists(false, emptyList(), emptyList(), emptyList())
    override suspend fun overlayJson(name: String): String? = null
}

internal object IosSettingsPlatform : SettingsPlatform {
    override fun pipAllowed() = false
    override fun openPipSettings() = Unit
    override fun notificationAccessAllowed() = false
    override fun openNotificationAccessSettings() = Unit
    override fun refreshPermissionWarnings(pipRequested: Boolean, phoneHudRequested: Boolean, mediaRateRequested: Boolean) = Unit

    // The app's own language, as the iPhone's per-app language setting stores
    // it. iOS applies it the next time the app starts.
    override fun applyAppLanguage(tag: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (tag.isBlank()) defaults.removeObjectForKey("AppleLanguages")
        else defaults.setObject(listOf(tag), forKey = "AppleLanguages")
    }

    override fun stopKeepAlive() = Unit
}
