package com.eried.eucplanet.ui.charging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.ChargingSnapshot
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/** Everything the Battery screen renders. */
data class ChargingUiState(
    val status: ChargeStatus = ChargeStatus.Disconnected,
    val connected: Boolean = false,
    val wheelName: String? = null,
    /** Current battery %, fractional when the wheel reports it. */
    val percent: Float = 0f,
    /** Battery % when this charging session started. */
    val startPercent: Float = 0f,
    /** % added since the session started (drives the green segment). */
    val addedPercent: Float = 0f,
    val voltage: Float = 0f,
    val current: Float = 0f,
    /** |V·I| when the wheel actually reports charge current; null otherwise. */
    val powerW: Int? = null,
    /** True when the wheel reports a real charge current (not the V14 ~0 A case). */
    val hasRealCurrent: Boolean = false,
    /** True when the wheel reports two distinct battery packs. */
    val hasPacks: Boolean = false,
    val maxTemp: Float = 0f,
    val battery1: Float = 0f,
    val battery2: Float = 0f,
    val ratePctPerMin: Float = 0f,
    val warmedUp: Boolean = false,
    val minutesToTarget: Float? = null,
    val minutesToFull: Float? = null,
    /** Smoothed absolute finish times (ms) — count down to these in real time. */
    val targetEtaMs: Long? = null,
    val fullEtaMs: Long? = null,
    val estimateToFull: Boolean = false,
    val targetPercent: Float = 80f,
    val chargeHistory: List<MetricSample> = emptyList(),
    val voltageHistory: List<MetricSample> = emptyList(),
    val tempHistory: List<MetricSample> = emptyList(),
)

/**
 * Thin mapper over [WheelRepository]. The charging session (estimator + history)
 * lives in the singleton repository, so leaving and re-entering this screen keeps
 * the running prediction instead of starting over.
 */
@HiltViewModel
class ChargingMonitorViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // Latched per connection so the Packs / Power tabs don't flicker in/out on a
    // momentary frame; reset on disconnect.
    private var seenPacks = false
    private var seenCurrent = false

    val uiState: StateFlow<ChargingUiState> = combine(
        wheelRepository.wheelData,
        wheelRepository.chargeStatus,
        wheelRepository.connectedDeviceName,
        wheelRepository.chargingSnapshot,
        settingsRepository.settings,
    ) { data, status, name, snap, settings ->
        buildState(data, status, name, snap, settings.chargingEstimateToFull)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seed with the repository's CURRENT values so reopening the screen starts
        // already at the charging state instead of replaying the fill/fade animation.
        buildState(
            wheelRepository.wheelData.value,
            wheelRepository.chargeStatus.value,
            wheelRepository.connectedDeviceName.value,
            wheelRepository.chargingSnapshot.value,
            false,
        ),
    )

    /** Toggle whether the prediction targets 100 % instead of 80 % (persisted). */
    fun setEstimateToFull(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(settingsRepository.get().copy(chargingEstimateToFull = value))
        }
    }

    private fun buildState(
        data: WheelData,
        status: ChargeStatus,
        name: String?,
        snap: ChargingSnapshot,
        estimateToFull: Boolean,
    ): ChargingUiState {
        val charging = status == ChargeStatus.Charging || status == ChargeStatus.Full
        val connected = status != ChargeStatus.Disconnected
        val est = snap.estimate
        if (!connected) {
            seenPacks = false
            seenCurrent = false
        } else {
            if (data.battery1Percent > 0f && data.battery2Percent > 0f) seenPacks = true
            // Only a real *charge* current latches the Power tab — the V14 reads
            // ~0 A while charging, so it never shows (and never blinks).
            if (charging && abs(data.current) > 0.5f) seenCurrent = true
        }
        val hasRealCurrent = seenCurrent
        val powerW = if (hasRealCurrent) abs(data.voltage * data.current).toInt() else null
        return ChargingUiState(
            status = status,
            connected = connected,
            wheelName = name,
            // Disconnected → 0 so the fill clears instead of showing the last level.
            percent = if (!connected) 0f else if (charging) est.percent else batteryPercentOf(data),
            startPercent = est.startPercent,
            // Signed: goes negative while discharging (the session runs whole connection).
            addedPercent = est.percent - est.startPercent,
            voltage = data.voltage,
            current = data.current,
            powerW = powerW,
            hasRealCurrent = hasRealCurrent,
            hasPacks = seenPacks,
            maxTemp = data.maxTemperature,
            battery1 = data.battery1Percent,
            battery2 = data.battery2Percent,
            ratePctPerMin = est.ratePctPerMin,
            warmedUp = est.warmedUp,
            minutesToTarget = est.minutesToTarget,
            minutesToFull = est.minutesToFull,
            targetEtaMs = snap.targetEtaMs,
            fullEtaMs = snap.fullEtaMs,
            estimateToFull = estimateToFull,
            chargeHistory = snap.chargeHistory,
            voltageHistory = snap.voltageHistory,
            tempHistory = snap.tempHistory,
        )
    }

    private fun batteryPercentOf(d: WheelData): Float = when {
        d.battery1Percent > 0f && d.battery2Percent > 0f -> (d.battery1Percent + d.battery2Percent) / 2f
        d.battery1Percent > 0f -> d.battery1Percent
        else -> d.batteryPercent.toFloat()
    }
}
