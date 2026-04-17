package com.eried.evendarkerbot.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.evendarkerbot.data.model.WheelData
import com.eried.evendarkerbot.data.repository.FullMetricHistory
import com.eried.evendarkerbot.data.repository.SettingsRepository
import com.eried.evendarkerbot.data.repository.WheelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MetricDetailViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {
    val wheelData: StateFlow<WheelData> = wheelRepository.wheelData
    val fullHistory: StateFlow<FullMetricHistory> = wheelRepository.fullHistory
    val imperialUnits: StateFlow<Boolean> = settingsRepository.settings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
