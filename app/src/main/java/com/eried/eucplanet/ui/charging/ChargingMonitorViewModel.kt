package com.eried.eucplanet.ui.charging

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.util.AppNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.ChargingSnapshot
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.data.repository.PredictionSample
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
    /** Disconnected, but the last session's data is still held and shown frozen. */
    val frozen: Boolean = false,
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
    /** Signed Wh integrated this session (+ charging, − discharging). */
    val energyWh: Float = 0f,
    /** Wh used while discharging this session (the ride loss). */
    val energyUsedWh: Float = 0f,
    /** Wh added while charging this session. */
    val energyChargedWh: Float = 0f,
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
    /** Per-pack cell-spread history (one list per pack) as min / max / avg cell
     *  voltage, plus the shared unit ("V" or "%") for the Packs tab band chart. */
    val packMinHistory: List<List<MetricSample>> = emptyList(),
    val packMaxHistory: List<List<MetricSample>> = emptyList(),
    val packAvgHistory: List<List<MetricSample>> = emptyList(),
    val packSeriesUnit: String = "%",
    /** Per-session snapshots of the running 80 % / 100 % predictions. */
    val predictionHistory: List<PredictionSample> = emptyList(),
    /** Stitched smart-BMS state. Empty packs list means this wheel hasn't
     *  reported per-cell data (older Sherman / KingSong / P6); the Cells tab
     *  hides in that case. */
    val bms: com.eried.eucplanet.data.model.BmsState = com.eried.eucplanet.data.model.BmsState(),
    // Cell / pack balance color thresholds (Advanced -> Charging), deviation
    // from the pack median.
    val cellLowWarnMv: Int = 30,
    val cellLowDangerMv: Int = 80,
    val cellHighMv: Int = 40,
    val packBalanceTolerancePct: Int = 5,
)

/**
 * Thin mapper over [WheelRepository]. The charging session (estimator + history)
 * lives in the singleton repository, so leaving and re-entering this screen keeps
 * the running prediction instead of starting over.
 */
@HiltViewModel
class ChargingMonitorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val appNotifier: AppNotifier,
) : ViewModel() {

    // Latched per connection so the Packs / Power tabs don't flicker in/out on a
    // momentary frame; reset on disconnect.
    private var seenPacks = false
    private var seenCurrent = false

    val uiState: StateFlow<ChargingUiState> = combine(
        combine(
            wheelRepository.wheelData,
            wheelRepository.chargeStatus,
            wheelRepository.connectedDeviceName,
            wheelRepository.chargingSnapshot,
        ) { data, status, name, snap -> Quad(data, status, name, snap) },
        settingsRepository.settings,
        wheelRepository.bmsState,
    ) { quad, settings, bms ->
        buildState(quad.data, quad.status, quad.name, quad.snap, settings.chargingEstimateToFull, bms,
            settings.advanced.cellLowWarnMv, settings.advanced.cellLowDangerMv,
            settings.advanced.cellHighMv, settings.advanced.packBalanceTolerancePct)
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
            wheelRepository.bmsState.value,
        ),
    )

    private data class Quad(
        val data: WheelData,
        val status: ChargeStatus,
        val name: String?,
        val snap: ChargingSnapshot,
    )

    /** Toggle whether the prediction targets 100 % instead of 80 % (persisted). */
    fun setEstimateToFull(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(settingsRepository.get().copy(chargingEstimateToFull = value))
        }
    }

    /** Wipe the charging session and start capturing fresh (Reset data menu item). */
    fun resetData() {
        wheelRepository.resetChargingSession()
    }

    /** Write the session CSV to [uri] (three-dots -> Export) and report the result. */
    fun saveCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val csv = buildCsvOrNull()
                if (csv == null) {
                    appNotifier.post(context.getString(R.string.charging_export_failed))
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                appNotifier.post(context.getString(R.string.charging_export_saved))
            } catch (e: Exception) {
                android.util.Log.w("ChargingMonitorVM", "saveCsv failed", e)
                appNotifier.post(context.getString(R.string.charging_export_failed))
            }
        }
    }

    /**
     * Build a CSV of the current charging session for the Export menu item: the
     * downsampled time series (charge %, voltage, temp, and each pack's cell-spread
     * min / max / avg) followed by a trailing block of the latest per-cell voltages.
     * Returns null when there is no session data yet. Pure - reads the UI state only.
     */
    private fun buildCsvOrNull(): String? {
        val s = uiState.value
        val charge = s.chargeHistory
        if (charge.isEmpty()) return null
        val volt = s.voltageHistory
        val temp = s.tempHistory
        val packMin = s.packMinHistory
        val packMax = s.packMaxHistory
        val packAvg = s.packAvgHistory
        val nPacks = packMin.size
        val unit = s.packSeriesUnit
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fun num(v: Float?, dp: Int) =
            if (v == null) "" else String.format(java.util.Locale.US, "%.${dp}f", v)
        val sb = StringBuilder()
        sb.append("time,elapsed_s,charge_pct,voltage_v,temp_c")
        for (p in 0 until nPacks) {
            sb.append(",pack${p + 1}_min_$unit,pack${p + 1}_max_$unit,pack${p + 1}_avg_$unit")
        }
        sb.append('\n')
        val t0 = charge.first().timestampMs
        for (i in charge.indices) {
            val t = charge[i].timestampMs
            sb.append(stamp.format(java.util.Date(t)))
            sb.append(',').append((t - t0) / 1000)
            sb.append(',').append(num(charge[i].value, 2))
            sb.append(',').append(num(volt.getOrNull(i)?.value, 3))
            sb.append(',').append(num(temp.getOrNull(i)?.value, 1))
            for (p in 0 until nPacks) {
                sb.append(',').append(num(packMin[p].getOrNull(i)?.value, 3))
                sb.append(',').append(num(packMax[p].getOrNull(i)?.value, 3))
                sb.append(',').append(num(packAvg[p].getOrNull(i)?.value, 3))
            }
            sb.append('\n')
        }
        // Latest per-cell voltages are not recorded over time, so append them as a
        // trailing snapshot block.
        if (s.bms.packs.any { it.knownCells.isNotEmpty() }) {
            sb.append("\npack,cell,voltage_v\n")
            s.bms.packs.forEachIndexed { pi, pack ->
                pack.knownCells.forEachIndexed { ci, cell ->
                    sb.append("${pi + 1},${ci + 1},").append(num(cell.second, 3)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun buildState(
        data: WheelData,
        status: ChargeStatus,
        name: String?,
        snap: ChargingSnapshot,
        estimateToFull: Boolean,
        bms: com.eried.eucplanet.data.model.BmsState = com.eried.eucplanet.data.model.BmsState(),
        cellLowWarnMv: Int = 30,
        cellLowDangerMv: Int = 80,
        cellHighMv: Int = 40,
        packBalanceTolerancePct: Int = 5,
    ): ChargingUiState {
        val charging = status == ChargeStatus.Charging || status == ChargeStatus.Full
        val connected = status != ChargeStatus.Disconnected
        val est = snap.estimate
        // Disconnected but the repository still holds the last session (it no longer
        // wipes on disconnect) -> keep showing it frozen instead of an empty screen.
        val frozen = !connected &&
            (snap.chargeHistory.isNotEmpty() || bms.hasCells || snap.packMinHistory.isNotEmpty())
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
            frozen = frozen,
            wheelName = name,
            // Connected: live level. Frozen: hold the last sampled level so the fill
            // stays put. Truly empty (no session): 0 so the gauge reads empty.
            percent = when {
                connected && charging -> est.percent
                connected -> batteryPercentOf(data)
                frozen -> est.percent
                else -> 0f
            },
            startPercent = est.startPercent,
            // Percent added since the charge low point. The estimator anchors on
            // the running minimum, so this reads ~0 while riding and climbs once
            // charging starts (it no longer goes negative on a pre-charge ride).
            addedPercent = est.percent - est.startPercent,
            voltage = data.voltage,
            current = data.current,
            powerW = powerW,
            hasRealCurrent = hasRealCurrent,
            // Keep the Packs tab available while frozen if the session recorded pack
            // history, so a disconnect doesn't hide the frozen band chart.
            hasPacks = seenPacks || (frozen && snap.packMinHistory.isNotEmpty()),
            maxTemp = data.maxTemperature,
            battery1 = data.battery1Percent,
            battery2 = data.battery2Percent,
            // At 100 % the charge is done — stop reporting a rate (the wheel just
            // balances cells, so any residual slope is noise, not charging).
            ratePctPerMin = if (status == ChargeStatus.Full) 0f else est.ratePctPerMin,
            energyWh = snap.sessionEnergyWh,
            energyUsedWh = snap.sessionEnergyOutWh,
            energyChargedWh = snap.sessionEnergyInWh,
            warmedUp = est.warmedUp,
            minutesToTarget = est.minutesToTarget,
            minutesToFull = est.minutesToFull,
            targetEtaMs = snap.targetEtaMs,
            fullEtaMs = snap.fullEtaMs,
            estimateToFull = estimateToFull,
            chargeHistory = snap.chargeHistory,
            voltageHistory = snap.voltageHistory,
            tempHistory = snap.tempHistory,
            packMinHistory = snap.packMinHistory,
            packMaxHistory = snap.packMaxHistory,
            packAvgHistory = snap.packAvgHistory,
            packSeriesUnit = snap.packSeriesUnit,
            predictionHistory = snap.predictionHistory,
            bms = bms,
            cellLowWarnMv = cellLowWarnMv,
            cellLowDangerMv = cellLowDangerMv,
            cellHighMv = cellHighMv,
            packBalanceTolerancePct = packBalanceTolerancePct,
        )
    }

    private fun batteryPercentOf(d: WheelData): Float = when {
        d.battery1Percent > 0f && d.battery2Percent > 0f -> (d.battery1Percent + d.battery2Percent) / 2f
        d.battery1Percent > 0f -> d.battery1Percent
        else -> d.batteryPercent.toFloat()
    }
}
