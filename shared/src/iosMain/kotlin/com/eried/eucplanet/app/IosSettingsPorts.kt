package com.eried.eucplanet.app

import com.eried.eucplanet.data.eucstats.RegisterResult
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.sync.BackupFolderSync
import com.eried.eucplanet.data.sync.RiderFileResult
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncConflictKind
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.service.AccelSplitTracker
import com.eried.eucplanet.service.ToneSynth
import com.eried.eucplanet.service.VoiceChoice
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.settings.EngineSoundPreview
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.ui.settings.OnlineLeaderboard
import com.eried.eucplanet.ui.settings.PromptTones
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
import kotlinx.coroutines.flow.combine
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
        val utterance = AVSpeechUtterance.speechUtteranceWithString(IosVoice.spoken(text))
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * speechRate).coerceIn(0.1f, 1f)
        voice.voiceFor(localeTag, voiceName)?.let { utterance.voice = it }
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
 * The backup folder ([IosFolderSync]) and Dropbox ([IosDropboxSync]), their
 * conflict question, progress and result surfaced the way SyncManager
 * surfaces them: one Sync all at a time, the question and the bar of
 * whichever runs. The folder rows and the settings backups are the shared
 * folder sync's, as on Android.
 */
internal object IosSettingsSync : SettingsSync, DashboardSync by IosDashboardSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val folder get() = IosFolderSync.sync

    override val activeSyncKind: StateFlow<SyncConflictKind?> =
        combine(IosFolderSync.running, IosDropboxSync.running) { f, d ->
            when {
                f -> SyncConflictKind.FOLDER
                d -> SyncConflictKind.DROPBOX
                else -> null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)
    override val syncCancelling: StateFlow<Boolean> =
        combine(IosFolderSync.cancelling, IosDropboxSync.cancelling) { f, d -> f || d }
            .stateIn(scope, SharingStarted.Eagerly, false)
    override val syncConflictFirstSettings: StateFlow<Boolean> =
        combine(IosFolderSync.conflictCount, IosDropboxSync.firstSettings) { f, d -> f == null && d }
            .stateIn(scope, SharingStarted.Eagerly, false)
    override val syncConflictKind: StateFlow<SyncConflictKind> = IosFolderSync.conflictCount
        .map { if (it != null) SyncConflictKind.FOLDER else SyncConflictKind.DROPBOX }
        .stateIn(scope, SharingStarted.Eagerly, SyncConflictKind.DROPBOX)
    override val syncConflictPrompt: StateFlow<Int?> =
        combine(IosFolderSync.conflictCount, IosDropboxSync.conflictCount) { f, d -> f ?: d }
            .stateIn(scope, SharingStarted.Eagerly, null)
    override val syncProgress: StateFlow<Pair<Int, Int>?> =
        combine(IosFolderSync.running, IosFolderSync.progress, IosDropboxSync.progress) { running, f, d ->
            if (running) f else d
        }.stateIn(scope, SharingStarted.Eagerly, null)
    override val syncResult: StateFlow<SyncResult?> =
        combine(IosFolderSync.result, IosDropboxSync.result) { f, d -> f ?: d }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun backupSettingsAs(name: String?, overwrite: Boolean) = folder.backupSettingsAs(name, overwrite)
    override fun cancelActiveSync() =
        if (IosFolderSync.running.value) IosFolderSync.cancelActive() else IosDropboxSync.cancelActive()
    override fun cancelDropboxSync() = IosDropboxSync.cancelQueuedPass()
    override fun cancelEucStatsUpload() = IosEucStats.cancel()
    override fun cancelSyncConflict() {
        IosFolderSync.resolve(SyncChoice.CANCEL)
        IosDropboxSync.resolve(SyncChoice.CANCEL)
    }
    override suspend fun clearSyncFolder() = IosFolderSync.clearFolder()
    override fun consumeSyncResult() {
        IosFolderSync.consumeResult()
        IosDropboxSync.consumeResult()
    }
    override suspend fun deleteRiderIdFile() = IosEucStats.deleteRiderId()
    override fun enqueueEucStatsUpload(settings: AppSettings) = IosEucStats.sweep()
    override fun enqueueTripUpload(settings: AppSettings) = folder.enqueueUpload(settings)
    // The rider id is a file in the app's own Documents, so it is the one this
    // phone has; with a folder, the folder's copy is hardened too, and a
    // different rider's id there is the mismatch to warn about.
    override suspend fun ensureRiderIdFile(storeId: String): RiderFileResult {
        val local = when (IosEucStats.readRiderId()) {
            storeId -> RiderFileResult.ALREADY_PRESENT
            null -> if (IosEucStats.writeRiderId(storeId)) RiderFileResult.WROTE else RiderFileResult.SKIPPED
            else -> RiderFileResult.MISMATCH
        }
        val inFolder = folder.ensureRiderIdFile(storeId)
        return if (inFolder == RiderFileResult.MISMATCH) RiderFileResult.MISMATCH else local
    }
    override val riderIdNeedsFolder = false
    override fun getSyncFolderDisplayName(settings: AppSettings): String? = folder.displayName(settings)
    override fun listFolderTripNames(settings: AppSettings): List<String>? = folder.listFolderTripNames(settings)
    override suspend fun resetAllEucstatsRows() = IosAppGraph.database.tripDao().resetAllEucstatsStatuses()
    override suspend fun resetUnfinishedEucstatsRows() = IosAppGraph.database.tripDao().resetUnfinishedEucstatsStatuses()
    override fun resolveSyncConflict(choice: SyncChoice) {
        // Only the sync that asked is waiting for an answer.
        if (IosFolderSync.conflictCount.value != null) IosFolderSync.resolve(choice) else IosDropboxSync.resolve(choice)
    }
    override fun sanitizeBackupName(raw: String): String? = BackupFolderSync.sanitizeBackupName(raw)
    override fun startDropboxSync() = IosDropboxSync.syncNow()
    override fun stopDropboxSync() = IosDropboxSync.stop()
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

/** The Motor sound previews, played by the same engine the ride drives (Android's AndroidEngineSoundPreview). */
internal class IosEngineSoundPreview(private val engine: EngineSoundEngine) : EngineSoundPreview {
    override fun previewProfile(
        key: String, durationMs: Long, muffler: String?, gearbox: String?, scenario: String,
    ) = engine.previewProfile(key, durationMs, muffler, gearbox, scenario)
}

/** The speed splits' session, as the settings screen shows and clears it (Android's AndroidSplitTimes). */
internal class IosSplitTimes(private val splits: AccelSplitRepository) : SplitTimes {
    override val session: StateFlow<AccelSplitTracker.Session> get() = splits.session
    override fun reset() = splits.reset()
}

internal object IosSettingsPlatform : SettingsPlatform {
    override fun pipAllowed() = false
    override fun openPipSettings() = Unit

    // The app's own language, as the iPhone's per-app language setting stores
    // it. iOS applies it the next time the app starts.
    override fun applyAppLanguage(tag: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (tag.isBlank()) defaults.removeObjectForKey("AppleLanguages")
        else defaults.setObject(listOf(tag), forKey = "AppleLanguages")
    }

    override fun stopKeepAlive() = Unit
}
