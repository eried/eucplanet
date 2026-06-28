package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsStore
) {
    val settings: Flow<AppSettings> = store.settings.map { it.sanitized() }

    suspend fun get(): AppSettings = store.get().sanitized()

    suspend fun update(settings: AppSettings) {
        store.update(settings)
    }

    /** Read-modify-write wrapper for callers that only want to change a
     *  field or two without echoing the whole [AppSettings] copy. Single
     *  read + write inside the same coroutine, so there's no torn-write
     *  window against the StateFlow. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        update(transform(get()))
    }

    suspend fun updateLastDevice(address: String, name: String) {
        val current = get()
        update(current.copy(lastDeviceAddress = address, lastDeviceName = name))
    }

    private fun AppSettings.sanitized(): AppSettings = copy(
        autoRecordStopIdleSeconds = autoRecordStopIdleSeconds.coerceAtLeast(30),
        // Advanced timing knobs: clamp to safe ranges so a 0 / negative / absurd
        // value (from an imported or Dropbox-synced settings file, not just the
        // Advanced steppers) can never busy-loop a delay(), divide by zero
        // (phoneGpsIntervalMs/2), or starve the BLE/IO loops. Every settings read
        // — get() and the settings Flow — passes through here, so the consumers
        // never see an unsafe value. Floors are well above zero on purpose.
        wheelPollIntervalMs = wheelPollIntervalMs.coerceIn(50, 5_000),
        graphSampleIntervalMs = graphSampleIntervalMs.coerceIn(200, 60_000),
        tripRecordIntervalMs = tripRecordIntervalMs.coerceIn(200, 60_000),
        phoneGpsIntervalMs = phoneGpsIntervalMs.coerceIn(200, 60_000),
        hudReportIntervalMs = hudReportIntervalMs.coerceIn(50, 10_000),
        garminReportIntervalMs = garminReportIntervalMs.coerceIn(50, 10_000),
        // Navigation timing (some feed delay() directly, so floors stay well > 0).
        navOffRouteGraceMs = navOffRouteGraceMs.coerceIn(500, 120_000),
        navOffRouteVoiceAfterMs = navOffRouteVoiceAfterMs.coerceIn(500, 120_000),
        navOffRouteVoiceCooldownMs = navOffRouteVoiceCooldownMs.coerceIn(1_000, 600_000),
        navRerouteAfterMs = navRerouteAfterMs.coerceIn(1_000, 600_000),
        navArrivalDismissMs = navArrivalDismissMs.coerceIn(1_000, 120_000),
        navHuntVoiceIntervalMs = navHuntVoiceIntervalMs.coerceIn(2_000, 600_000),
        navHeadingWindowMs = navHeadingWindowMs.coerceIn(1_000, 120_000),
        navFixBufferMs = navFixBufferMs.coerceIn(1_000, 120_000),
        navIntermediateFlashMs = navIntermediateFlashMs.coerceIn(250, 30_000),
        navPopupTimeoutMs = navPopupTimeoutMs.coerceIn(1_000, 60_000),
        // Predictive-alarm trend window.
        alarmSlopeWindowMs = alarmSlopeWindowMs.coerceIn(300, 10_000),
        alarmBufferMaxMs = alarmBufferMaxMs.coerceIn(500, 20_000),
        alarmSlopeMinSamples = alarmSlopeMinSamples.coerceIn(2, 20),
        alarmSlopeMinSpanMs = alarmSlopeMinSpanMs.coerceIn(50, 5_000),
        // Radar + automation.
        radarClearDecayMs = radarClearDecayMs.coerceIn(250, 30_000),
        automationLightCheckIntervalMs = automationLightCheckIntervalMs.coerceIn(5_000, 600_000),
        // HUD discovery / reconnection.
        hudBackoffMinMs = hudBackoffMinMs.coerceIn(100, 60_000),
        hudBackoffMaxMs = hudBackoffMaxMs.coerceIn(500, 120_000),
        hudMdnsTimeoutMs = hudMdnsTimeoutMs.coerceIn(500, 60_000),
        hudDiscoverySprintMs = hudDiscoverySprintMs.coerceIn(1_000, 300_000),
        // Auto-lights.
        autoLightNoGpsRetryMs = autoLightNoGpsRetryMs.coerceIn(250, 60_000),
        autoToggleGraceMs = autoToggleGraceMs.coerceIn(250, 60_000),
        // Navigation thresholds.
        navMovingKmh = navMovingKmh.coerceIn(1, 50),
        navPrepareDistM = navPrepareDistM.coerceIn(20, 2_000),
        navExecuteDistM = navExecuteDistM.coerceIn(5, 500),
        navProxBandM = navProxBandM.coerceIn(1, 100),
        navMinInterStopMoveM = navMinInterStopMoveM.coerceIn(5, 500),
        // Radar threat classification.
        radarFastApproachDistM = radarFastApproachDistM.coerceIn(5, 500),
        radarFastApproachSpeedKmh = radarFastApproachSpeedKmh.coerceIn(5, 200),
        radarStaticTargetKmh = radarStaticTargetKmh.coerceIn(1, 50),
        radarFallbackClosingMps = radarFallbackClosingMps.coerceIn(1, 100),
        radarMinFrameRateMs = radarMinFrameRateMs.coerceIn(20, 5_000),
        // Charging-ETA estimator (taper factors x100).
        chargingTargetPercent = chargingTargetPercent.coerceIn(50, 99),
        chargingTargetTaperX100 = chargingTargetTaperX100.coerceIn(100, 300),
        chargingCvTaperX100 = chargingCvTaperX100.coerceIn(100, 500),
        chargingWarmupMinPercentGain = chargingWarmupMinPercentGain.coerceIn(1, 50),
        chargingWarmupMinDurationMs = chargingWarmupMinDurationMs.coerceIn(5_000, 600_000),
        chargingWindowMs = chargingWindowMs.coerceIn(30_000, 1_200_000),
        chargingSanityCapMinutes = chargingSanityCapMinutes.coerceIn(60, 1_440),
        chargingMedianFilterSize = chargingMedianFilterSize.coerceIn(1, 21),
    )
}
