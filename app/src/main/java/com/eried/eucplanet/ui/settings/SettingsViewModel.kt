package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.location.Location
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.service.VoiceService
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val tripRepository: TripRepository,
    private val syncManager: SyncManager
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val maxSpeedCap: StateFlow<Float> = wheelRepository.maxSpeedCap

    val isConnected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val availableVoices: StateFlow<List<VoiceOption>> = voiceService.availableVoices

    val currentLocation: StateFlow<Location?> = tripRepository.currentLocation

    private fun update(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.transform())
        }
    }

    fun updateTiltbackSpeed(value: Float) {
        update {
            copy(
                tiltbackSpeedKmh = value,
                alarmSpeedKmh = alarmSpeedKmh.coerceAtMost(value),
                safetyTiltbackKmh = safetyTiltbackKmh.coerceAtMost(value - 1f),
                safetyAlarmKmh = safetyAlarmKmh.coerceAtMost((value - 1f).coerceAtLeast(10f))
            )
        }
        viewModelScope.launch {
            val s = settingsRepository.get()
            wheelRepository.setSpeed(value, s.alarmSpeedKmh.coerceAtMost(value))
        }
    }

    fun updateAlarmSpeed(value: Float) {
        update { copy(alarmSpeedKmh = value, tiltbackSpeedKmh = tiltbackSpeedKmh.coerceAtLeast(value)) }
        viewModelScope.launch {
            val s = settingsRepository.get()
            wheelRepository.setSpeed(s.tiltbackSpeedKmh.coerceAtLeast(value), value)
        }
    }
    fun updateSafetyTiltback(value: Float) =
        update {
            val capped = value.coerceAtMost(tiltbackSpeedKmh - 1f)
            copy(safetyTiltbackKmh = capped, safetyAlarmKmh = safetyAlarmKmh.coerceAtMost(capped))
        }

    fun updateSafetyAlarm(value: Float) =
        update { copy(safetyAlarmKmh = value, safetyTiltbackKmh = safetyTiltbackKmh.coerceAtLeast(value)) }
    fun updateVoiceEnabled(enabled: Boolean) = update { copy(voiceEnabled = enabled) }
    fun updateVoiceOnlyWhenConnected(enabled: Boolean) = update { copy(voiceOnlyWhenConnected = enabled) }
    fun updateVoiceInterval(seconds: Int) = update { copy(voiceIntervalSeconds = seconds) }
    fun updateVoiceSpeechRate(v: Float) = update { copy(voiceSpeechRate = v) }
    fun updateVoiceReportSpeed(v: Boolean) = update { copy(voiceReportSpeed = v) }
    fun updateVoiceReportBattery(v: Boolean) = update { copy(voiceReportBattery = v) }
    fun updateVoiceReportTemp(v: Boolean) = update { copy(voiceReportTemp = v) }
    fun updateVoiceReportPwm(v: Boolean) = update { copy(voiceReportPwm = v) }
    fun updateVoiceReportDistance(v: Boolean) = update { copy(voiceReportDistance = v) }
    fun updateTriggerReportSpeed(v: Boolean) = update { copy(triggerReportSpeed = v) }
    fun updateTriggerReportBattery(v: Boolean) = update { copy(triggerReportBattery = v) }
    fun updateTriggerReportTemp(v: Boolean) = update { copy(triggerReportTemp = v) }
    fun updateTriggerReportPwm(v: Boolean) = update { copy(triggerReportPwm = v) }
    fun updateTriggerReportDistance(v: Boolean) = update { copy(triggerReportDistance = v) }
    fun updateVoiceLocale(tag: String) {
        update { copy(voiceLocale = tag) }
        voiceService.setVoiceLocale(tag)
    }
    fun updateAutoRecord(v: Boolean) = update { copy(autoRecord = v) }
    fun updateAutoConnect(v: Boolean) = update { copy(autoConnect = v) }

    // Automations
    fun updateAutoLightsEnabled(v: Boolean) = update { copy(autoLightsEnabled = v) }
    fun updateAutoLightsOnMinutes(v: Int) = update { copy(autoLightsOnMinutesBefore = v) }
    fun updateAutoLightsOffMinutes(v: Int) = update { copy(autoLightsOffMinutesAfter = v) }
    fun updateAutoVolumeEnabled(v: Boolean) = update { copy(autoVolumeEnabled = v) }
    fun updateAutoVolumeCurve(curve: String) = update { copy(autoVolumeCurve = curve) }

    // Voice report: recording
    fun updateVoiceReportRecording(v: Boolean) = update { copy(voiceReportRecording = v) }
    fun updateTriggerReportRecording(v: Boolean) = update { copy(triggerReportRecording = v) }

    fun testSpeak(text: String) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            voiceService.testSpeak(text, s.voiceSpeechRate, s.voiceLocale)
        }
    }

    // Special announcements
    fun updateAnnounceWheelLock(v: Boolean) = update { copy(announceWheelLock = v) }
    fun updateAnnounceLights(v: Boolean) = update { copy(announceLights = v) }
    fun updateAnnounceRecording(v: Boolean) = update { copy(announceRecording = v) }
    fun updateAnnounceConnection(v: Boolean) = update { copy(announceConnection = v) }
    fun updateAnnounceGps(v: Boolean) = update { copy(announceGps = v) }
    fun updateAnnounceSafetyMode(v: Boolean) = update { copy(announceSafetyMode = v) }
    fun updateAnnounceWelcome(v: Boolean) = update { copy(announceWelcome = v) }

    fun updateVoiceReportOrder(order: String) = update { copy(voiceReportOrder = order) }

    fun updateImperialUnits(v: Boolean) = update { copy(imperialUnits = v) }

    private val _ttsSwitchPrompt = MutableStateFlow<String?>(null)
    val ttsSwitchPrompt: StateFlow<String?> = _ttsSwitchPrompt.asStateFlow()

    // Appearance
    fun updateLanguage(v: String) {
        update { copy(language = v) }
        com.eried.eucplanet.util.LocaleHelper.apply(v)
        val appLang = if (v.isBlank()) "en" else v
        val ttsLang = voiceService.currentVoiceLanguage().lowercase()
        if (ttsLang != appLang.lowercase()) {
            _ttsSwitchPrompt.value = appLang
        }
    }

    fun acceptTtsSwitch() {
        val lang = _ttsSwitchPrompt.value ?: return
        val tag = voiceService.pickVoiceForLanguage(lang)
        if (tag != null) {
            update { copy(voiceLocale = tag) }
            voiceService.setVoiceLocale(tag)
        }
        _ttsSwitchPrompt.value = null
    }

    fun dismissTtsSwitch() { _ttsSwitchPrompt.value = null }

    fun updateThemeMode(v: String) = update { copy(themeMode = v) }
    fun updateAccentColor(v: String) = update { copy(accentColor = v) }

    // Volume keys
    fun updateVolumeKeysEnabled(v: Boolean) = update { copy(volumeKeysEnabled = v) }
    fun updateVolumeUpClick(v: String) = update { copy(volumeUpClick = v) }
    fun updateVolumeUpHold(v: String) = update { copy(volumeUpHold = v) }
    fun updateVolumeDownClick(v: String) = update { copy(volumeDownClick = v) }
    fun updateVolumeDownHold(v: String) = update { copy(volumeDownHold = v) }

    // Cloud sync
    private val _cloudEvent = MutableStateFlow<CloudEvent?>(null)
    val cloudEvent: StateFlow<CloudEvent?> = _cloudEvent.asStateFlow()

    fun consumeCloudEvent() { _cloudEvent.value = null }

    fun updateSyncFolder(uri: Uri) {
        viewModelScope.launch {
            val ok = syncManager.setSyncFolder(uri)
            _cloudEvent.value = if (ok) CloudEvent.FolderSet else CloudEvent.FolderFailed
        }
    }

    fun clearSyncFolder() {
        viewModelScope.launch { syncManager.clearSyncFolder() }
    }

    fun backupSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.backupSettings()
            _cloudEvent.value = if (ok) CloudEvent.BackupSuccess else CloudEvent.BackupFailed
        }
    }

    fun restoreSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.restoreSettings()
            _cloudEvent.value = if (ok) CloudEvent.RestoreSuccess else CloudEvent.RestoreFailed
        }
    }

    fun retryUploadsNow() {
        viewModelScope.launch {
            val s = settingsRepository.get()
            syncManager.enqueueTripUpload(s)
            _cloudEvent.value = CloudEvent.UploadEnqueued
        }
    }

    fun moveReportItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = current.voiceReportOrder.split(",").map { it.trim() }.toMutableList()
            if (fromIndex in items.indices && toIndex in items.indices) {
                val item = items.removeAt(fromIndex)
                items.add(toIndex, item)
                settingsRepository.update(current.copy(voiceReportOrder = items.joinToString(",")))
            }
        }
    }

    fun syncFolderDisplayName(): String? {
        val s = settings.value ?: return null
        return syncManager.getSyncFolderDisplayName(s)
    }
}

enum class CloudEvent {
    FolderSet, FolderFailed,
    BackupSuccess, BackupFailed,
    RestoreSuccess, RestoreFailed,
    UploadEnqueued
}
