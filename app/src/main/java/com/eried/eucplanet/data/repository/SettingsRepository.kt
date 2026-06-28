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
        // Advanced timing knobs (nested in AppSettings.advanced): clamp to safe
        // ranges so a 0 / negative / absurd value (from an imported or
        // Dropbox-synced settings file, not just the Advanced steppers) can never
        // busy-loop a delay(), divide by zero (phoneGpsIntervalMs/2), or starve
        // the BLE/IO loops. Every settings read — get() and the settings Flow —
        // passes through here, so consumers never see an unsafe value.
        advanced = advanced.copy(
            wheelPollIntervalMs = advanced.wheelPollIntervalMs.coerceIn(50, 5_000),
            graphSampleIntervalMs = advanced.graphSampleIntervalMs.coerceIn(200, 60_000),
            tripRecordIntervalMs = advanced.tripRecordIntervalMs.coerceIn(200, 60_000),
            phoneGpsIntervalMs = advanced.phoneGpsIntervalMs.coerceIn(200, 60_000),
            hudReportIntervalMs = advanced.hudReportIntervalMs.coerceIn(50, 10_000),
            garminReportIntervalMs = advanced.garminReportIntervalMs.coerceIn(50, 10_000),
            navOffRouteGraceMs = advanced.navOffRouteGraceMs.coerceIn(500, 120_000),
            navOffRouteVoiceAfterMs = advanced.navOffRouteVoiceAfterMs.coerceIn(500, 120_000),
            navOffRouteVoiceCooldownMs = advanced.navOffRouteVoiceCooldownMs.coerceIn(1_000, 600_000),
            navRerouteAfterMs = advanced.navRerouteAfterMs.coerceIn(1_000, 600_000),
            navArrivalDismissMs = advanced.navArrivalDismissMs.coerceIn(1_000, 120_000),
            navHuntVoiceIntervalMs = advanced.navHuntVoiceIntervalMs.coerceIn(2_000, 600_000),
            navHeadingWindowMs = advanced.navHeadingWindowMs.coerceIn(1_000, 120_000),
            navFixBufferMs = advanced.navFixBufferMs.coerceIn(1_000, 120_000),
            navIntermediateFlashMs = advanced.navIntermediateFlashMs.coerceIn(250, 30_000),
            navPopupTimeoutMs = advanced.navPopupTimeoutMs.coerceIn(1_000, 60_000),
            alarmSlopeWindowMs = advanced.alarmSlopeWindowMs.coerceIn(300, 10_000),
            alarmBufferMaxMs = advanced.alarmBufferMaxMs.coerceIn(500, 20_000),
            alarmSlopeMinSamples = advanced.alarmSlopeMinSamples.coerceIn(2, 20),
            alarmSlopeMinSpanMs = advanced.alarmSlopeMinSpanMs.coerceIn(50, 5_000),
            radarClearDecayMs = advanced.radarClearDecayMs.coerceIn(250, 30_000),
            automationLightCheckIntervalMs = advanced.automationLightCheckIntervalMs.coerceIn(5_000, 600_000),
            hudBackoffMinMs = advanced.hudBackoffMinMs.coerceIn(100, 60_000),
            hudBackoffMaxMs = advanced.hudBackoffMaxMs.coerceIn(500, 120_000),
            hudMdnsTimeoutMs = advanced.hudMdnsTimeoutMs.coerceIn(500, 60_000),
            hudDiscoverySprintMs = advanced.hudDiscoverySprintMs.coerceIn(1_000, 300_000),
            autoLightNoGpsRetryMs = advanced.autoLightNoGpsRetryMs.coerceIn(250, 60_000),
            autoToggleGraceMs = advanced.autoToggleGraceMs.coerceIn(250, 60_000),
            navMovingKmh = advanced.navMovingKmh.coerceIn(1, 50),
            navPrepareDistM = advanced.navPrepareDistM.coerceIn(20, 2_000),
            navExecuteDistM = advanced.navExecuteDistM.coerceIn(5, 500),
            navProxBandM = advanced.navProxBandM.coerceIn(1, 100),
            navMinInterStopMoveM = advanced.navMinInterStopMoveM.coerceIn(5, 500),
            radarFastApproachDistM = advanced.radarFastApproachDistM.coerceIn(5, 500),
            radarFastApproachSpeedKmh = advanced.radarFastApproachSpeedKmh.coerceIn(5, 200),
            radarStaticTargetKmh = advanced.radarStaticTargetKmh.coerceIn(1, 50),
            radarFallbackClosingMps = advanced.radarFallbackClosingMps.coerceIn(1, 100),
            radarMinFrameRateMs = advanced.radarMinFrameRateMs.coerceIn(20, 5_000),
            chargingTargetPercent = advanced.chargingTargetPercent.coerceIn(50, 99),
            chargingTargetTaperX100 = advanced.chargingTargetTaperX100.coerceIn(100, 300),
            chargingCvTaperX100 = advanced.chargingCvTaperX100.coerceIn(100, 500),
            chargingWarmupMinPercentGain = advanced.chargingWarmupMinPercentGain.coerceIn(1, 50),
            chargingWarmupMinDurationMs = advanced.chargingWarmupMinDurationMs.coerceIn(5_000, 600_000),
            chargingWindowMs = advanced.chargingWindowMs.coerceIn(30_000, 1_200_000),
            chargingSanityCapMinutes = advanced.chargingSanityCapMinutes.coerceIn(60, 1_440),
            chargingMedianFilterSize = advanced.chargingMedianFilterSize.coerceIn(1, 21),
        ),
    )
}
