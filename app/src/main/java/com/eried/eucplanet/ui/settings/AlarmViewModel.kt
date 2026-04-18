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
    settingsRepository: SettingsRepository
) : ViewModel() {

    val imperialUnits: StateFlow<Boolean> = settingsRepository.settings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    private val vibratorHelper = VibratorHelper(context)

    val rules: StateFlow<List<AlarmRule>> = alarmDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun previewVoice(text: String, metric: AlarmMetric) {
        val metricLabel = context.getString(metric.labelRes)
        val preview = text.ifBlank { context.getString(R.string.alarm_test_default) }
            .replace("{speed}", "35")
            .replace("{battery}", "80")
            .replace("{temp}", "40")
            .replace("{pwm}", "65")
            .replace("{voltage}", "100")
            .replace("{current}", "12")
            .replace("{trip}", "10")
            .replace("{value}", "35")
            .replace("{metric}", metricLabel)
            .replace("{threshold}", "30")
        voiceService.speak(preview)
    }

    fun previewVibrate(durationMs: Int) {
        vibratorHelper.oneShot(durationMs.toLong())
    }
}
