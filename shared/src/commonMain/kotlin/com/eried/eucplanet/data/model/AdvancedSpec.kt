package com.eried.eucplanet.data.model

import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource

/**
 * Data-driven registry for the Advanced settings section.
 *
 * Each tunable is declared once as an [AdvancedSpec]; the UI, the clamping in
 * SettingsRepository.sanitized(), JSON (de)serialization, the per-setting update,
 * and the restore-default affordance all iterate this list instead of repeating
 * a hand-written row / function / clamp line per knob. Adding a knob = one field on
 * [AdvancedSettings] + one spec entry + two strings.
 *
 * Mirrors the project's existing `ThemeTokens.specs` registry pattern.
 *
 * Defaults are NOT stored here: they live on [AdvancedSettings]' constructor
 * defaults (single source of truth) and are read via [ADVANCED_DEFAULTS] /
 * [AdvancedSpec.default]. The value is always an Int; [format]/[parse] own the
 * display, so the decimal "x" taper factors (stored x100) need no special case.
 */
enum class AdvGroup(
    val titleRes: StringResource,
    val warningRes: StringResource? = null,   // info box shown under the header
    val noteRes: StringResource? = null,      // hint shown after the rows
) {
    RATES(Res.string.adv_group_rates, noteRes = Res.string.adv_pushonly_note),
    NAV_TIMING(Res.string.adv_group_nav, warningRes = Res.string.adv_nav_warning),
    ALARM(Res.string.adv_group_alarm, warningRes = Res.string.adv_alarm_warning),
    RADAR_AUTO(Res.string.adv_group_radar_auto),
    HUD(Res.string.adv_group_hud),
    AUTOLIGHTS(Res.string.adv_group_autolights),
    NAV_BEHAVIOUR(Res.string.adv_group_nav_behaviour),
    RADAR_CLASS(Res.string.adv_group_radar_class, warningRes = Res.string.adv_radar_warning),
    CHARGING(Res.string.adv_group_charging, warningRes = Res.string.adv_charging_warning),
    GEOMETRY(Res.string.adv_group_geometry),
    MAP_CACHE(Res.string.adv_group_map_cache),
    CONTROLS(Res.string.adv_group_controls),
    WEATHER(Res.string.adv_group_weather),
}

data class AdvancedSpec(
    val id: String,                 // also the JSON key (kept stable for back-compat)
    val group: AdvGroup,
    val label: StringResource,
    val desc: StringResource,
    val range: IntRange,            // also the clamp range used by sanitized()
    val step: Int,
    val unit: String = "ms",
    val get: (AdvancedSettings) -> Int,
    val set: (AdvancedSettings, Int) -> AdvancedSettings,
    val format: (Int) -> String = { it.toString() },
    val parse: (String) -> Int? = { it.toIntOrNull() },
    val allowSign: Boolean = false, // needed so the field accepts a decimal point
)

/** Decimal "x" factor display for the charging taper steppers (value stored x100). */
val taperFormat: (Int) -> String = { String.format(Locale.US, "%.2f", it / 100f) }
val taperParse: (String) -> Int? = { it.toFloatOrNull()?.let { f -> javaRound(f * 100f) } }

/** `Math.round(Float)`: NaN rounds to 0 instead of throwing. */
private fun javaRound(f: Float): Int = if (f.isNaN()) 0 else f.roundToInt()

/** 6-digit PIN display: keep leading zeros so 0 shows as "000000". */
val pinFormat: (Int) -> String = { String.format(Locale.US, "%06d", it) }
val pinParse: (String) -> Int? = { it.toIntOrNull() }

/** Canonical defaults, one allocation, reused for resets, JSON fallback, etc. */
val ADVANCED_DEFAULTS = AdvancedSettings()

/** A spec's default value = the matching field on [ADVANCED_DEFAULTS]. */
fun AdvancedSpec.default(): Int = get(ADVANCED_DEFAULTS)

val ADVANCED_SPECS: List<AdvancedSpec> = listOf(
    // --- Data and reporting rates ---
    AdvancedSpec("wheelPollIntervalMs", AdvGroup.RATES, Res.string.adv_wheel_poll_rate, Res.string.adv_wheel_poll_rate_desc,
        50..2000, 25, get = { it.wheelPollIntervalMs }, set = { s, v -> s.copy(wheelPollIntervalMs = v) }),
    AdvancedSpec("graphSampleIntervalMs", AdvGroup.RATES, Res.string.adv_graph_sample_interval, Res.string.adv_graph_sample_interval_desc,
        250..10000, 250, get = { it.graphSampleIntervalMs }, set = { s, v -> s.copy(graphSampleIntervalMs = v) }),
    AdvancedSpec("smoothingWindowSamples", AdvGroup.RATES, Res.string.adv_smoothing_window, Res.string.adv_smoothing_window_desc,
        2..120, 1, unit = "samples", get = { it.smoothingWindowSamples }, set = { s, v -> s.copy(smoothingWindowSamples = v) }),
    AdvancedSpec("tripRecordIntervalMs", AdvGroup.RATES, Res.string.adv_trip_record_interval, Res.string.adv_trip_record_interval_desc,
        250..10000, 250, get = { it.tripRecordIntervalMs }, set = { s, v -> s.copy(tripRecordIntervalMs = v) }),
    AdvancedSpec("pendingUploadIntervalMin", AdvGroup.RATES, Res.string.adv_pending_upload_interval, Res.string.adv_pending_upload_interval_desc,
        15..360, 15, unit = "min", get = { it.pendingUploadIntervalMin }, set = { s, v -> s.copy(pendingUploadIntervalMin = v) }),
    AdvancedSpec("tripFinalizeGraceMs", AdvGroup.RATES, Res.string.adv_trip_finalize_grace, Res.string.adv_trip_finalize_grace_desc,
        5000..60000, 1000, get = { it.tripFinalizeGraceMs }, set = { s, v -> s.copy(tripFinalizeGraceMs = v) }),
    AdvancedSpec("voiceListenWindowSec", AdvGroup.CONTROLS, Res.string.adv_voice_listen_window, Res.string.adv_voice_listen_window_desc,
        3..30, 1, unit = "s", get = { it.voiceListenWindowSec }, set = { s, v -> s.copy(voiceListenWindowSec = v) }),
    AdvancedSpec("lockMaxSpeedKmh", AdvGroup.CONTROLS, Res.string.adv_lock_max_speed, Res.string.adv_lock_max_speed_desc,
        0..20, 1, unit = "km/h", get = { it.lockMaxSpeedKmh }, set = { s, v -> s.copy(lockMaxSpeedKmh = v) }),
    AdvancedSpec("phoneGpsIntervalMs", AdvGroup.RATES, Res.string.adv_phone_gps_interval, Res.string.adv_phone_gps_interval_desc,
        250..10000, 250, get = { it.phoneGpsIntervalMs }, set = { s, v -> s.copy(phoneGpsIntervalMs = v) }),
    AdvancedSpec("phoneGpsIdleIntervalMs", AdvGroup.RATES, Res.string.adv_phone_gps_idle_interval, Res.string.adv_phone_gps_idle_interval_desc,
        2000..120000, 1000, get = { it.phoneGpsIdleIntervalMs }, set = { s, v -> s.copy(phoneGpsIdleIntervalMs = v) }),
    AdvancedSpec("gpsIdleOffDelaySec", AdvGroup.RATES, Res.string.adv_gps_idle_off_delay, Res.string.adv_gps_idle_off_delay_desc,
        0..300, 15, unit = "s", get = { it.gpsIdleOffDelaySec }, set = { s, v -> s.copy(gpsIdleOffDelaySec = v) }),
    AdvancedSpec("gpsFixMaxAgeSec", AdvGroup.RATES, Res.string.adv_gps_fix_max_age, Res.string.adv_gps_fix_max_age_desc,
        3..30, 1, unit = "s", get = { it.gpsFixMaxAgeSec }, set = { s, v -> s.copy(gpsFixMaxAgeSec = v) }),
    AdvancedSpec("hudReportIntervalMs", AdvGroup.RATES, Res.string.adv_hud_report_interval, Res.string.adv_hud_report_interval_desc,
        50..2000, 25, get = { it.hudReportIntervalMs }, set = { s, v -> s.copy(hudReportIntervalMs = v) }),
    AdvancedSpec("garminReportIntervalMs", AdvGroup.RATES, Res.string.adv_garmin_report_interval, Res.string.adv_garmin_report_interval_desc,
        100..2000, 25, get = { it.garminReportIntervalMs }, set = { s, v -> s.copy(garminReportIntervalMs = v) }),

    AdvancedSpec("headlightReadbackMaxAgeMs", AdvGroup.CONTROLS,
        Res.string.adv_headlight_readback_age, Res.string.adv_headlight_readback_age_desc,
        1000..30000, 1000, get = { it.headlightReadbackMaxAgeMs },
        set = { s, v -> s.copy(headlightReadbackMaxAgeMs = v) }),

    // --- Navigation timing ---
    AdvancedSpec("navOffRouteGraceMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_offroute_grace, Res.string.adv_nav_offroute_grace_desc,
        500..60000, 500, get = { it.navOffRouteGraceMs }, set = { s, v -> s.copy(navOffRouteGraceMs = v) }),
    AdvancedSpec("navRerouteAfterMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_reroute_after, Res.string.adv_nav_reroute_after_desc,
        1000..120000, 1000, get = { it.navRerouteAfterMs }, set = { s, v -> s.copy(navRerouteAfterMs = v) }),
    AdvancedSpec("navOffRouteVoiceAfterMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_offroute_voice_after, Res.string.adv_nav_offroute_voice_after_desc,
        500..60000, 500, get = { it.navOffRouteVoiceAfterMs }, set = { s, v -> s.copy(navOffRouteVoiceAfterMs = v) }),
    AdvancedSpec("navOffRouteVoiceCooldownMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_offroute_voice_cooldown, Res.string.adv_nav_offroute_voice_cooldown_desc,
        1000..120000, 1000, get = { it.navOffRouteVoiceCooldownMs }, set = { s, v -> s.copy(navOffRouteVoiceCooldownMs = v) }),
    AdvancedSpec("navArrivalDismissMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_arrival_dismiss, Res.string.adv_nav_arrival_dismiss_desc,
        1000..60000, 1000, get = { it.navArrivalDismissMs }, set = { s, v -> s.copy(navArrivalDismissMs = v) }),
    AdvancedSpec("navHuntVoiceIntervalMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_hunt_voice_interval, Res.string.adv_nav_hunt_voice_interval_desc,
        2000..120000, 1000, get = { it.navHuntVoiceIntervalMs }, set = { s, v -> s.copy(navHuntVoiceIntervalMs = v) }),
    AdvancedSpec("navHeadingWindowMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_heading_window, Res.string.adv_nav_heading_window_desc,
        1000..60000, 500, get = { it.navHeadingWindowMs }, set = { s, v -> s.copy(navHeadingWindowMs = v) }),
    AdvancedSpec("navFixBufferMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_fix_buffer, Res.string.adv_nav_fix_buffer_desc,
        1000..60000, 1000, get = { it.navFixBufferMs }, set = { s, v -> s.copy(navFixBufferMs = v) }),
    AdvancedSpec("navIntermediateFlashMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_intermediate_flash, Res.string.adv_nav_intermediate_flash_desc,
        250..10000, 250, get = { it.navIntermediateFlashMs }, set = { s, v -> s.copy(navIntermediateFlashMs = v) }),
    AdvancedSpec("navPopupTimeoutMs", AdvGroup.NAV_TIMING, Res.string.adv_nav_popup_timeout, Res.string.adv_nav_popup_timeout_desc,
        1000..30000, 500, get = { it.navPopupTimeoutMs }, set = { s, v -> s.copy(navPopupTimeoutMs = v) }),

    // --- Predictive alarms ---
    AdvancedSpec("alarmSlopeWindowMs", AdvGroup.ALARM, Res.string.adv_alarm_slope_window, Res.string.adv_alarm_slope_window_desc,
        300..10000, 100, get = { it.alarmSlopeWindowMs }, set = { s, v -> s.copy(alarmSlopeWindowMs = v) }),
    AdvancedSpec("alarmBufferMaxMs", AdvGroup.ALARM, Res.string.adv_alarm_buffer_max, Res.string.adv_alarm_buffer_max_desc,
        500..20000, 100, get = { it.alarmBufferMaxMs }, set = { s, v -> s.copy(alarmBufferMaxMs = v) }),
    AdvancedSpec("alarmSlopeMinSamples", AdvGroup.ALARM, Res.string.adv_alarm_min_samples, Res.string.adv_alarm_min_samples_desc,
        2..20, 1, unit = "", get = { it.alarmSlopeMinSamples }, set = { s, v -> s.copy(alarmSlopeMinSamples = v) }),
    AdvancedSpec("alarmSlopeMinSpanMs", AdvGroup.ALARM, Res.string.adv_alarm_min_span, Res.string.adv_alarm_min_span_desc,
        50..5000, 50, get = { it.alarmSlopeMinSpanMs }, set = { s, v -> s.copy(alarmSlopeMinSpanMs = v) }),
    AdvancedSpec("alarmWarmRouteMs", AdvGroup.ALARM, Res.string.adv_alarm_warm_route, Res.string.adv_alarm_warm_route_desc,
        10000..3600000, 10000, get = { it.alarmWarmRouteMs }, set = { s, v -> s.copy(alarmWarmRouteMs = v) }),

    // --- Radar and automation ---
    AdvancedSpec("radarClearDecayMs", AdvGroup.RADAR_AUTO, Res.string.adv_radar_clear_decay, Res.string.adv_radar_clear_decay_desc,
        250..30000, 250, get = { it.radarClearDecayMs }, set = { s, v -> s.copy(radarClearDecayMs = v) }),
    AdvancedSpec("automationLightCheckIntervalMs", AdvGroup.RADAR_AUTO, Res.string.adv_automation_light_check, Res.string.adv_automation_light_check_desc,
        5000..600000, 5000, get = { it.automationLightCheckIntervalMs }, set = { s, v -> s.copy(automationLightCheckIntervalMs = v) }),

    // --- HUD link ---
    AdvancedSpec("hudBackoffMinMs", AdvGroup.HUD, Res.string.adv_hud_backoff_min, Res.string.adv_hud_backoff_min_desc,
        100..30000, 100, get = { it.hudBackoffMinMs }, set = { s, v -> s.copy(hudBackoffMinMs = v) }),
    AdvancedSpec("hudBackoffMaxMs", AdvGroup.HUD, Res.string.adv_hud_backoff_max, Res.string.adv_hud_backoff_max_desc,
        500..60000, 500, get = { it.hudBackoffMaxMs }, set = { s, v -> s.copy(hudBackoffMaxMs = v) }),
    AdvancedSpec("hudMdnsTimeoutMs", AdvGroup.HUD, Res.string.adv_hud_mdns_timeout, Res.string.adv_hud_mdns_timeout_desc,
        500..30000, 500, get = { it.hudMdnsTimeoutMs }, set = { s, v -> s.copy(hudMdnsTimeoutMs = v) }),
    AdvancedSpec("hudDiscoverySprintMs", AdvGroup.HUD, Res.string.adv_hud_discovery_sprint, Res.string.adv_hud_discovery_sprint_desc,
        1000..120000, 1000, get = { it.hudDiscoverySprintMs }, set = { s, v -> s.copy(hudDiscoverySprintMs = v) }),
    AdvancedSpec("hudDiscoveryTotalTimeoutMs", AdvGroup.HUD, Res.string.adv_hud_discovery_total, Res.string.adv_hud_discovery_total_desc,
        5000..60000, 1000, get = { it.hudDiscoveryTotalTimeoutMs }, set = { s, v -> s.copy(hudDiscoveryTotalTimeoutMs = v) }),
    AdvancedSpec("hudUdpProbeTimeoutMs", AdvGroup.HUD, Res.string.adv_hud_udp_probe, Res.string.adv_hud_udp_probe_desc,
        3000..30000, 500, get = { it.hudUdpProbeTimeoutMs }, set = { s, v -> s.copy(hudUdpProbeTimeoutMs = v) }),
    AdvancedSpec("hudUdpBeaconFreshnessMs", AdvGroup.HUD, Res.string.adv_hud_udp_freshness, Res.string.adv_hud_udp_freshness_desc,
        3000..30000, 500, get = { it.hudUdpBeaconFreshnessMs }, set = { s, v -> s.copy(hudUdpBeaconFreshnessMs = v) }),
    AdvancedSpec("hudUdpPollIntervalMs", AdvGroup.HUD, Res.string.adv_hud_udp_poll, Res.string.adv_hud_udp_poll_desc,
        50..1000, 50, get = { it.hudUdpPollIntervalMs }, set = { s, v -> s.copy(hudUdpPollIntervalMs = v) }),
    AdvancedSpec("hudManualHintDelayMs", AdvGroup.HUD, Res.string.adv_hud_manual_grace, Res.string.adv_hud_manual_grace_desc,
        0..5000, 100, get = { it.hudManualHintDelayMs }, set = { s, v -> s.copy(hudManualHintDelayMs = v) }),
    AdvancedSpec("hudMdnsServiceInfoTimeoutMs", AdvGroup.HUD, Res.string.adv_hud_mdns_resolve, Res.string.adv_hud_mdns_resolve_desc,
        200..5000, 100, get = { it.hudMdnsServiceInfoTimeoutMs }, set = { s, v -> s.copy(hudMdnsServiceInfoTimeoutMs = v) }),
    AdvancedSpec("hudIdleSearchMs", AdvGroup.HUD, Res.string.adv_hud_idle_search, Res.string.adv_hud_idle_search_desc,
        10000..1800000, 10000, get = { it.hudIdleSearchMs }, set = { s, v -> s.copy(hudIdleSearchMs = v) }),
    AdvancedSpec("hudSweepIntervalMs", AdvGroup.HUD, Res.string.adv_hud_sweep_interval, Res.string.adv_hud_sweep_interval_desc,
        5000..600000, 5000, get = { it.hudSweepIntervalMs }, set = { s, v -> s.copy(hudSweepIntervalMs = v) }),

    // --- Auto-lights ---
    AdvancedSpec("autoLightNoGpsRetryMs", AdvGroup.AUTOLIGHTS, Res.string.adv_autolight_no_gps_retry, Res.string.adv_autolight_no_gps_retry_desc,
        250..30000, 250, get = { it.autoLightNoGpsRetryMs }, set = { s, v -> s.copy(autoLightNoGpsRetryMs = v) }),
    AdvancedSpec("autoToggleGraceMs", AdvGroup.AUTOLIGHTS, Res.string.adv_autolight_toggle_grace, Res.string.adv_autolight_toggle_grace_desc,
        250..30000, 250, get = { it.autoToggleGraceMs }, set = { s, v -> s.copy(autoToggleGraceMs = v) }),

    // --- Navigation behaviour ---
    AdvancedSpec("navMovingKmh", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_moving, Res.string.adv_nav_moving_desc,
        1..50, 1, unit = "km/h", get = { it.navMovingKmh }, set = { s, v -> s.copy(navMovingKmh = v) }),
    AdvancedSpec("navCruiseKmh", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_cruise, Res.string.adv_nav_cruise_desc,
        10..60, 1, unit = "km/h", get = { it.navCruiseKmh }, set = { s, v -> s.copy(navCruiseKmh = v) }),
    AdvancedSpec("navPrepareDistM", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_prepare_dist, Res.string.adv_nav_prepare_dist_desc,
        20..2000, 10, unit = "m", get = { it.navPrepareDistM }, set = { s, v -> s.copy(navPrepareDistM = v) }),
    AdvancedSpec("navExecuteDistM", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_execute_dist, Res.string.adv_nav_execute_dist_desc,
        5..500, 5, unit = "m", get = { it.navExecuteDistM }, set = { s, v -> s.copy(navExecuteDistM = v) }),
    AdvancedSpec("navProxBandM", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_prox_band, Res.string.adv_nav_prox_band_desc,
        1..100, 1, unit = "m", get = { it.navProxBandM }, set = { s, v -> s.copy(navProxBandM = v) }),
    AdvancedSpec("navMinInterStopMoveM", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_inter_stop, Res.string.adv_nav_inter_stop_desc,
        5..500, 5, unit = "m", get = { it.navMinInterStopMoveM }, set = { s, v -> s.copy(navMinInterStopMoveM = v) }),
    AdvancedSpec("navMaxStartDistanceKm", AdvGroup.NAV_BEHAVIOUR, Res.string.adv_nav_max_start, Res.string.adv_nav_max_start_desc,
        5..1000, 5, unit = "km", get = { it.navMaxStartDistanceKm }, set = { s, v -> s.copy(navMaxStartDistanceKm = v) }),

    // --- Radar classification ---
    AdvancedSpec("radarFastApproachDistM", AdvGroup.RADAR_CLASS, Res.string.adv_radar_fast_dist, Res.string.adv_radar_fast_dist_desc,
        5..500, 5, unit = "m", get = { it.radarFastApproachDistM }, set = { s, v -> s.copy(radarFastApproachDistM = v) }),
    AdvancedSpec("radarFastApproachSpeedKmh", AdvGroup.RADAR_CLASS, Res.string.adv_radar_fast_speed, Res.string.adv_radar_fast_speed_desc,
        5..200, 5, unit = "km/h", get = { it.radarFastApproachSpeedKmh }, set = { s, v -> s.copy(radarFastApproachSpeedKmh = v) }),
    AdvancedSpec("radarStaticTargetKmh", AdvGroup.RADAR_CLASS, Res.string.adv_radar_static, Res.string.adv_radar_static_desc,
        1..50, 1, unit = "km/h", get = { it.radarStaticTargetKmh }, set = { s, v -> s.copy(radarStaticTargetKmh = v) }),
    AdvancedSpec("radarFallbackClosingMps", AdvGroup.RADAR_CLASS, Res.string.adv_radar_fallback, Res.string.adv_radar_fallback_desc,
        1..100, 1, unit = "m/s", get = { it.radarFallbackClosingMps }, set = { s, v -> s.copy(radarFallbackClosingMps = v) }),
    AdvancedSpec("radarMinFrameRateMs", AdvGroup.RADAR_CLASS, Res.string.adv_radar_min_frame, Res.string.adv_radar_min_frame_desc,
        20..5000, 10, get = { it.radarMinFrameRateMs }, set = { s, v -> s.copy(radarMinFrameRateMs = v) }),

    // --- Charging ETA ---
    AdvancedSpec("chargingTargetPercent", AdvGroup.CHARGING, Res.string.adv_charging_target_pct, Res.string.adv_charging_target_pct_desc,
        50..99, 1, unit = "%", get = { it.chargingTargetPercent }, set = { s, v -> s.copy(chargingTargetPercent = v) }),
    AdvancedSpec("chargingTargetTaperX100", AdvGroup.CHARGING, Res.string.adv_charging_target_taper, Res.string.adv_charging_target_taper_desc,
        100..300, 5, unit = "x", get = { it.chargingTargetTaperX100 }, set = { s, v -> s.copy(chargingTargetTaperX100 = v) },
        format = taperFormat, parse = taperParse, allowSign = true),
    AdvancedSpec("chargingCvTaperX100", AdvGroup.CHARGING, Res.string.adv_charging_cv_taper, Res.string.adv_charging_cv_taper_desc,
        100..500, 5, unit = "x", get = { it.chargingCvTaperX100 }, set = { s, v -> s.copy(chargingCvTaperX100 = v) },
        format = taperFormat, parse = taperParse, allowSign = true),
    AdvancedSpec("chargingWarmupMinPercentGain", AdvGroup.CHARGING, Res.string.adv_charging_warmup_gain, Res.string.adv_charging_warmup_gain_desc,
        1..50, 1, unit = "%", get = { it.chargingWarmupMinPercentGain }, set = { s, v -> s.copy(chargingWarmupMinPercentGain = v) }),
    AdvancedSpec("chargingWarmupMinDurationMs", AdvGroup.CHARGING, Res.string.adv_charging_warmup_dur, Res.string.adv_charging_warmup_dur_desc,
        5000..600000, 5000, get = { it.chargingWarmupMinDurationMs }, set = { s, v -> s.copy(chargingWarmupMinDurationMs = v) }),
    AdvancedSpec("chargingWindowMs", AdvGroup.CHARGING, Res.string.adv_charging_window, Res.string.adv_charging_window_desc,
        30000..1200000, 30000, get = { it.chargingWindowMs }, set = { s, v -> s.copy(chargingWindowMs = v) }),
    AdvancedSpec("chargingSanityCapMinutes", AdvGroup.CHARGING, Res.string.adv_charging_sanity_cap, Res.string.adv_charging_sanity_cap_desc,
        60..1440, 30, unit = "min", get = { it.chargingSanityCapMinutes }, set = { s, v -> s.copy(chargingSanityCapMinutes = v) }),
    AdvancedSpec("chargingMedianFilterSize", AdvGroup.CHARGING, Res.string.adv_charging_median, Res.string.adv_charging_median_desc,
        1..21, 2, unit = "", get = { it.chargingMedianFilterSize }, set = { s, v -> s.copy(chargingMedianFilterSize = v) }),

    // --- Battery cell / pack balance coloring (deviation from the pack median) ---
    AdvancedSpec("cellLowWarnMv", AdvGroup.CHARGING, Res.string.adv_cell_low_warn, Res.string.adv_cell_low_warn_desc,
        5..150, 5, unit = "mV", get = { it.cellLowWarnMv }, set = { s, v -> s.copy(cellLowWarnMv = v) }),
    AdvancedSpec("cellLowDangerMv", AdvGroup.CHARGING, Res.string.adv_cell_low_danger, Res.string.adv_cell_low_danger_desc,
        10..300, 5, unit = "mV", get = { it.cellLowDangerMv }, set = { s, v -> s.copy(cellLowDangerMv = v) }),
    AdvancedSpec("cellHighMv", AdvGroup.CHARGING, Res.string.adv_cell_high, Res.string.adv_cell_high_desc,
        5..200, 5, unit = "mV", get = { it.cellHighMv }, set = { s, v -> s.copy(cellHighMv = v) }),
    AdvancedSpec("packBalanceTolerancePct", AdvGroup.CHARGING, Res.string.adv_pack_balance_tol, Res.string.adv_pack_balance_tol_desc,
        1..30, 1, unit = "%", get = { it.packBalanceTolerancePct }, set = { s, v -> s.copy(packBalanceTolerancePct = v) }),

    // --- Screen geometry variables ---
    AdvancedSpec("compactMaxScreenDp", AdvGroup.GEOMETRY, Res.string.adv_compact_max_screen, Res.string.adv_compact_max_screen_desc,
        300..800, 10, unit = "dp", get = { it.compactMaxScreenDp }, set = { s, v -> s.copy(compactMaxScreenDp = v) }),
    AdvancedSpec("coverCutoutInsetDp", AdvGroup.GEOMETRY, Res.string.adv_cover_cutout_inset, Res.string.adv_cover_cutout_inset_desc,
        48..200, 4, unit = "dp", get = { it.coverCutoutInsetDp }, set = { s, v -> s.copy(coverCutoutInsetDp = v) }),
    AdvancedSpec("simpleSpeedoScalePct", AdvGroup.GEOMETRY, Res.string.adv_simple_speedo_scale, Res.string.adv_simple_speedo_scale_desc,
        40..90, 2, unit = "%", get = { it.simpleSpeedoScalePct }, set = { s, v -> s.copy(simpleSpeedoScalePct = v) }),
    AdvancedSpec("navSidebarWidthDp", AdvGroup.GEOMETRY, Res.string.adv_nav_sidebar_width, Res.string.adv_nav_sidebar_width_desc,
        240..480, 10, unit = "dp", get = { it.navSidebarWidthDp }, set = { s, v -> s.copy(navSidebarWidthDp = v) }),
    AdvancedSpec("navSidebarMinScreenDp", AdvGroup.GEOMETRY, Res.string.adv_nav_sidebar_min, Res.string.adv_nav_sidebar_min_desc,
        400..900, 20, unit = "dp", get = { it.navSidebarMinScreenDp }, set = { s, v -> s.copy(navSidebarMinScreenDp = v) }),

    // --- Map cache budgets ---
    AdvancedSpec("mapEncodedCacheMiB", AdvGroup.MAP_CACHE, Res.string.adv_map_encoded_cache, Res.string.adv_map_encoded_cache_desc,
        1..32, 1, unit = "MiB", get = { it.mapEncodedCacheMiB }, set = { s, v -> s.copy(mapEncodedCacheMiB = v) }),
    AdvancedSpec("mapHttpCacheMiB", AdvGroup.MAP_CACHE, Res.string.adv_map_http_cache, Res.string.adv_map_http_cache_desc,
        16..256, 16, unit = "MiB", get = { it.mapHttpCacheMiB }, set = { s, v -> s.copy(mapHttpCacheMiB = v) }),

    // --- Wheel access ---
    // InMotion V1 (V5/V8/V10/L6) BLE PIN. Typed 6-digit field; factory default
    // 000000. The +/- steppers are incidental (you type the PIN); NumberUpDown
    // already limits input to 6 digits.
    AdvancedSpec("inmotionV1Pin", AdvGroup.CONTROLS, Res.string.adv_inmotion_v1_pin, Res.string.adv_inmotion_v1_pin_desc,
        0..999999, 1, unit = "", get = { it.inmotionV1Pin }, set = { s, v -> s.copy(inmotionV1Pin = v) },
        format = pinFormat, parse = pinParse),
    AdvancedSpec("kingsongUnlockCode", AdvGroup.CONTROLS, Res.string.adv_kingsong_unlock_code, Res.string.adv_kingsong_unlock_code_desc,
        0..999999, 1, unit = "", get = { it.kingsongUnlockCode }, set = { s, v -> s.copy(kingsongUnlockCode = v) },
        format = pinFormat, parse = pinParse),
    AdvancedSpec("kingsongPassword", AdvGroup.CONTROLS, Res.string.adv_kingsong_password, Res.string.adv_kingsong_password_desc,
        0..9999, 1, unit = "", get = { it.kingsongPassword }, set = { s, v -> s.copy(kingsongPassword = v) },
        format = { String.format(Locale.US, "%04d", it) }, parse = pinParse),

    // --- Weather score thresholds ---
    // The comfort numbers behind the ridability score; the riding
    // preferences in Navigation & weather say HOW each condition counts,
    // these say WHEN it applies. Ids double as the JSON keys the values used
    // to live under inside WeatherSettings, so tuned setups carry over.
    AdvancedSpec("weatherColdC", AdvGroup.WEATHER, Res.string.weather_cold, Res.string.adv_weather_cold_desc,
        -30..25, 1, unit = "°C", allowSign = true,
        get = { it.weatherColdC }, set = { s, v -> s.copy(weatherColdC = v) }),
    AdvancedSpec("weatherHotC", AdvGroup.WEATHER, Res.string.weather_hot, Res.string.adv_weather_hot_desc,
        -29..55, 1, unit = "°C", allowSign = true,
        get = { it.weatherHotC }, set = { s, v -> s.copy(weatherHotC = v) }),
    AdvancedSpec("weatherBreezyTenthsMs", AdvGroup.WEATHER, Res.string.weather_breezy, Res.string.adv_weather_breezy_desc,
        0..200, 5, unit = "m/s", allowSign = true,
        get = { it.weatherBreezyTenthsMs }, set = { s, v -> s.copy(weatherBreezyTenthsMs = v) },
        format = { String.format(Locale.US, "%.1f", it / 10f) },
        parse = { it.replace(',', '.').toFloatOrNull()?.let { f -> javaRound(f * 10f) } }),
    AdvancedSpec("weatherWindyTenthsMs", AdvGroup.WEATHER, Res.string.weather_windy, Res.string.adv_weather_windy_desc,
        5..400, 5, unit = "m/s", allowSign = true,
        get = { it.weatherWindyTenthsMs }, set = { s, v -> s.copy(weatherWindyTenthsMs = v) },
        format = { String.format(Locale.US, "%.1f", it / 10f) },
        parse = { it.replace(',', '.').toFloatOrNull()?.let { f -> javaRound(f * 10f) } }),
)
