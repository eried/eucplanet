package com.eried.eucplanet.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.VibratorHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlarmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private val RADAR_METRIC_NAMES = setOf(
            AlarmMetric.RADAR_DISTANCE.name,
            AlarmMetric.RADAR_APPROACH_SPEED.name
        )
    }

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "kmh")

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "km")

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "C")

    /**
     * The rider's chosen voice locale, fed to the alarm editor so a newly
     * created alarm's default voice template is in the language the TTS
     * engine actually speaks (not the UI language - users with English UI
     * + Spanish voice should get "¡Atención!"). Placeholders like {metric}
     * stay literal regardless of locale.
     */
    val voiceLocale: StateFlow<String> = settingsRepository.settings
        .map { it.voiceLocale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "en_US")


    private val vibratorHelper = VibratorHelper(context)

    val rules: StateFlow<List<AlarmRule>> = alarmDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The radar alarm metrics (RADAR_DISTANCE, RADAR_APPROACH_SPEED) only
     * show up in the New-alarm metric dropdown when this is true. The flag
     * stays sticky for users who restored a backup with radar rules or who
     * temporarily unpaired their radar, the rule editor never hides a
     * metric that an existing rule is using.
     */
    val showRadarMetrics: StateFlow<Boolean> = combine(
        settingsRepository.settings.map { it.radarAddress != null },
        alarmDao.observeAll().map { it.any { r -> r.metric in RADAR_METRIC_NAMES } }
    ) { paired, hasRadarRule -> paired || hasRadarRule }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addRule(rule: AlarmRule) {
        viewModelScope.launch {
            val order = alarmDao.nextSortOrder()
            alarmDao.insert(rule.copy(sortOrder = order))
        }
    }

    fun updateRule(rule: AlarmRule) {
        viewModelScope.launch { alarmDao.update(rule) }
    }

    fun deleteRule(rule: AlarmRule) {
        viewModelScope.launch { alarmDao.delete(rule) }
    }

    fun moveUp(rule: AlarmRule) {
        viewModelScope.launch {
            val all = rules.value.toMutableList()
            val idx = all.indexOfFirst { it.id == rule.id }
            if (idx > 0) {
                val prev = all[idx - 1]
                alarmDao.update(rule.copy(sortOrder = prev.sortOrder))
                alarmDao.update(prev.copy(sortOrder = rule.sortOrder))
            }
        }
    }

    fun moveDown(rule: AlarmRule) {
        viewModelScope.launch {
            val all = rules.value.toMutableList()
            val idx = all.indexOfFirst { it.id == rule.id }
            if (idx >= 0 && idx < all.size - 1) {
                val next = all[idx + 1]
                alarmDao.update(rule.copy(sortOrder = next.sortOrder))
                alarmDao.update(next.copy(sortOrder = rule.sortOrder))
            }
        }
    }

    fun previewBeep(frequencyHz: Int, durationMs: Int, count: Int) {
        viewModelScope.launch {
            tonePlayer.playBeep(frequencyHz, durationMs, count)
        }
    }

    fun previewVoice(text: String, metric: AlarmMetric, threshold: Float) {
        val metricLabel = context.getString(metric.voiceLabelRes)
        // {value} and {threshold} both read back the rider's chosen threshold,
        // so the preview sounds like the alarm they actually configured.
        val thresh = "%.0f".format(threshold)
        val preview = text.ifBlank { context.getString(R.string.alarm_test_default) }
            .replace("{speed}", "35")
            .replace("{battery}", "80")
            .replace("{temp}", "40")
            .replace("{pwm}", "65")
            .replace("{voltage}", "100")
            .replace("{current}", "12")
            .replace("{trip}", "10")
            .replace("{value}", thresh)
            .replace("{metric}", metricLabel)
            .replace("{threshold}", thresh)
        voiceService.speak(preview)
    }

    fun previewVibrate(durationMs: Int) {
        vibratorHelper.oneShot(durationMs.toLong())
    }
}
