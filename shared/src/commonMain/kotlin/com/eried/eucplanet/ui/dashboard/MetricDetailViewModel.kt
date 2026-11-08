package com.eried.eucplanet.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MetricDetailViewModel(
    private val wheelRepository: WheelRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {
    val wheelData: StateFlow<WheelData> = wheelRepository.wheelData
    val fullHistory: StateFlow<FullMetricHistory> = wheelRepository.fullHistory
    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "kmh")

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "km")

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "C")

    /** The rider's own pressure unit, for the tyre-pressure graph and stats. */
    val pressureUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectivePressureUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "bar")

    /** Wipe this metric's rolling history; new samples re-seed at the next 1Hz tick. */
    fun resetHistory(key: String) = wheelRepository.resetHistory(key)

    /** Wipe every metric's rolling history. */
    fun resetAllHistory() = wheelRepository.resetAllHistory()
}

/**
 * The metric detail's route argument, as the dashboard hands it over:
 *   "BATTERY"               one tab
 *   "SPEED,BATTERY,POWER"   three tabs, the first selected
 *   "SPEED,BATTERY,POWER|2" three tabs, POWER selected
 * The "|<index>" suffix lets a composite tile's side tap pre-select a tab
 * without changing the tab order.
 */
data class MetricDetailRoute(val keys: List<String>, val initialIndex: Int) {
    companion object {
        fun parse(arg: String): MetricDetailRoute {
            val (keysPart, idxPart) = arg.split("|", limit = 2)
                .let { if (it.size == 2) it[0] to it[1] else arg to "0" }
            val keys = keysPart.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val initialIdx = idxPart.toIntOrNull()?.coerceIn(0, (keys.size - 1).coerceAtLeast(0)) ?: 0
            return MetricDetailRoute(keys, initialIdx)
        }
    }
}
