package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import com.eried.eucplanet.R

/**
 * Data-driven registry for the Advanced settings section.
 *
 * Each tunable is declared once as an [AdvancedSpec]; the UI, the clamping in
 * SettingsRepository.sanitized(), JSON (de)serialization, the per-setting update,
 * and the restore-default affordance all iterate this list instead of repeating
 * 46 hand-written rows / functions / clamp lines. Adding a knob = one field on
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
    @StringRes val titleRes: Int,
    @StringRes val warningRes: Int? = null,   // info box shown under the header
    @StringRes val noteRes: Int? = null,      // hint shown after the rows
) {
    RATES(R.string.adv_group_rates, noteRes = R.string.adv_pushonly_note),
    NAV_TIMING(R.string.adv_group_nav, warningRes = R.string.adv_nav_warning),
    ALARM(R.string.adv_group_alarm, warningRes = R.string.adv_alarm_warning),
    RADAR_AUTO(R.string.adv_group_radar_auto),
    HUD(R.string.adv_group_hud),
    AUTOLIGHTS(R.string.adv_group_autolights),
    NAV_BEHAVIOUR(R.string.adv_group_nav_behaviour),
    RADAR_CLASS(R.string.adv_group_radar_class, warningRes = R.string.adv_radar_warning),
    CHARGING(R.string.adv_group_charging, warningRes = R.string.adv_charging_warning),
    GEOMETRY(R.string.adv_group_geometry),
}

data class AdvancedSpec(
    val id: String,                 // also the JSON key (kept stable for back-compat)
    val group: AdvGroup,
    @StringRes val label: Int,
    @StringRes val desc: Int,
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
val taperFormat: (Int) -> String = { String.format(java.util.Locale.US, "%.2f", it / 100f) }
val taperParse: (String) -> Int? = { it.toFloatOrNull()?.let { f -> Math.round(f * 100f) } }

/** Canonical defaults — one allocation, reused for resets, JSON fallback, etc. */
val ADVANCED_DEFAULTS = AdvancedSettings()

/** A spec's default value = the matching field on [ADVANCED_DEFAULTS]. */
fun AdvancedSpec.default(): Int = get(ADVANCED_DEFAULTS)

val ADVANCED_SPECS: List<AdvancedSpec> = listOf(
    // --- Data and reporting rates ---
    AdvancedSpec("wheelPollIntervalMs", AdvGroup.RATES, R.string.adv_wheel_poll_rate, R.string.adv_wheel_poll_rate_desc,
        50..2000, 25, get = { it.wheelPollIntervalMs }, set = { s, v -> s.copy(wheelPollIntervalMs = v) }),
    AdvancedSpec("graphSampleIntervalMs", AdvGroup.RATES, R.string.adv_graph_sample_interval, R.string.adv_graph_sample_interval_desc,
        250..10000, 250, get = { it.graphSampleIntervalMs }, set = { s, v -> s.copy(graphSampleIntervalMs = v) }),
    AdvancedSpec("tripRecordIntervalMs", AdvGroup.RATES, R.string.adv_trip_record_interval, R.string.adv_trip_record_interval_desc,
        250..10000, 250, get = { it.tripRecordIntervalMs }, set = { s, v -> s.copy(tripRecordIntervalMs = v) }),
    AdvancedSpec("phoneGpsIntervalMs", AdvGroup.RATES, R.string.adv_phone_gps_interval, R.string.adv_phone_gps_interval_desc,
        250..10000, 250, get = { it.phoneGpsIntervalMs }, set = { s, v -> s.copy(phoneGpsIntervalMs = v) }),
    AdvancedSpec("hudReportIntervalMs", AdvGroup.RATES, R.string.adv_hud_report_interval, R.string.adv_hud_report_interval_desc,
        50..2000, 25, get = { it.hudReportIntervalMs }, set = { s, v -> s.copy(hudReportIntervalMs = v) }),
    AdvancedSpec("garminReportIntervalMs", AdvGroup.RATES, R.string.adv_garmin_report_interval, R.string.adv_garmin_report_interval_desc,
        100..2000, 25, get = { it.garminReportIntervalMs }, set = { s, v -> s.copy(garminReportIntervalMs = v) }),

    // --- Navigation timing ---
    AdvancedSpec("navOffRouteGraceMs", AdvGroup.NAV_TIMING, R.string.adv_nav_offroute_grace, R.string.adv_nav_offroute_grace_desc,
        500..60000, 500, get = { it.navOffRouteGraceMs }, set = { s, v -> s.copy(navOffRouteGraceMs = v) }),
    AdvancedSpec("navRerouteAfterMs", AdvGroup.NAV_TIMING, R.string.adv_nav_reroute_after, R.string.adv_nav_reroute_after_desc,
        1000..120000, 1000, get = { it.navRerouteAfterMs }, set = { s, v -> s.copy(navRerouteAfterMs = v) }),
    AdvancedSpec("navOffRouteVoiceAfterMs", AdvGroup.NAV_TIMING, R.string.adv_nav_offroute_voice_after, R.string.adv_nav_offroute_voice_after_desc,
        500..60000, 500, get = { it.navOffRouteVoiceAfterMs }, set = { s, v -> s.copy(navOffRouteVoiceAfterMs = v) }),
    AdvancedSpec("navOffRouteVoiceCooldownMs", AdvGroup.NAV_TIMING, R.string.adv_nav_offroute_voice_cooldown, R.string.adv_nav_offroute_voice_cooldown_desc,
        1000..120000, 1000, get = { it.navOffRouteVoiceCooldownMs }, set = { s, v -> s.copy(navOffRouteVoiceCooldownMs = v) }),
    AdvancedSpec("navArrivalDismissMs", AdvGroup.NAV_TIMING, R.string.adv_nav_arrival_dismiss, R.string.adv_nav_arrival_dismiss_desc,
        1000..60000, 1000, get = { it.navArrivalDismissMs }, set = { s, v -> s.copy(navArrivalDismissMs = v) }),
    AdvancedSpec("navHuntVoiceIntervalMs", AdvGroup.NAV_TIMING, R.string.adv_nav_hunt_voice_interval, R.string.adv_nav_hunt_voice_interval_desc,
        2000..120000, 1000, get = { it.navHuntVoiceIntervalMs }, set = { s, v -> s.copy(navHuntVoiceIntervalMs = v) }),
    AdvancedSpec("navHeadingWindowMs", AdvGroup.NAV_TIMING, R.string.adv_nav_heading_window, R.string.adv_nav_heading_window_desc,
        1000..60000, 500, get = { it.navHeadingWindowMs }, set = { s, v -> s.copy(navHeadingWindowMs = v) }),
    AdvancedSpec("navFixBufferMs", AdvGroup.NAV_TIMING, R.string.adv_nav_fix_buffer, R.string.adv_nav_fix_buffer_desc,
        1000..60000, 1000, get = { it.navFixBufferMs }, set = { s, v -> s.copy(navFixBufferMs = v) }),
    AdvancedSpec("navIntermediateFlashMs", AdvGroup.NAV_TIMING, R.string.adv_nav_intermediate_flash, R.string.adv_nav_intermediate_flash_desc,
        250..10000, 250, get = { it.navIntermediateFlashMs }, set = { s, v -> s.copy(navIntermediateFlashMs = v) }),
    AdvancedSpec("navPopupTimeoutMs", AdvGroup.NAV_TIMING, R.string.adv_nav_popup_timeout, R.string.adv_nav_popup_timeout_desc,
        1000..30000, 500, get = { it.navPopupTimeoutMs }, set = { s, v -> s.copy(navPopupTimeoutMs = v) }),

    // --- Predictive alarms ---
    AdvancedSpec("alarmSlopeWindowMs", AdvGroup.ALARM, R.string.adv_alarm_slope_window, R.string.adv_alarm_slope_window_desc,
        300..10000, 100, get = { it.alarmSlopeWindowMs }, set = { s, v -> s.copy(alarmSlopeWindowMs = v) }),
    AdvancedSpec("alarmBufferMaxMs", AdvGroup.ALARM, R.string.adv_alarm_buffer_max, R.string.adv_alarm_buffer_max_desc,
        500..20000, 100, get = { it.alarmBufferMaxMs }, set = { s, v -> s.copy(alarmBufferMaxMs = v) }),
    AdvancedSpec("alarmSlopeMinSamples", AdvGroup.ALARM, R.string.adv_alarm_min_samples, R.string.adv_alarm_min_samples_desc,
        2..20, 1, unit = "", get = { it.alarmSlopeMinSamples }, set = { s, v -> s.copy(alarmSlopeMinSamples = v) }),
    AdvancedSpec("alarmSlopeMinSpanMs", AdvGroup.ALARM, R.string.adv_alarm_min_span, R.string.adv_alarm_min_span_desc,
        50..5000, 50, get = { it.alarmSlopeMinSpanMs }, set = { s, v -> s.copy(alarmSlopeMinSpanMs = v) }),

    // --- Radar and automation ---
    AdvancedSpec("radarClearDecayMs", AdvGroup.RADAR_AUTO, R.string.adv_radar_clear_decay, R.string.adv_radar_clear_decay_desc,
        250..30000, 250, get = { it.radarClearDecayMs }, set = { s, v -> s.copy(radarClearDecayMs = v) }),
    AdvancedSpec("automationLightCheckIntervalMs", AdvGroup.RADAR_AUTO, R.string.adv_automation_light_check, R.string.adv_automation_light_check_desc,
        5000..600000, 5000, get = { it.automationLightCheckIntervalMs }, set = { s, v -> s.copy(automationLightCheckIntervalMs = v) }),

    // --- HUD link ---
    AdvancedSpec("hudBackoffMinMs", AdvGroup.HUD, R.string.adv_hud_backoff_min, R.string.adv_hud_backoff_min_desc,
        100..30000, 100, get = { it.hudBackoffMinMs }, set = { s, v -> s.copy(hudBackoffMinMs = v) }),
    AdvancedSpec("hudBackoffMaxMs", AdvGroup.HUD, R.string.adv_hud_backoff_max, R.string.adv_hud_backoff_max_desc,
        500..60000, 500, get = { it.hudBackoffMaxMs }, set = { s, v -> s.copy(hudBackoffMaxMs = v) }),
    AdvancedSpec("hudMdnsTimeoutMs", AdvGroup.HUD, R.string.adv_hud_mdns_timeout, R.string.adv_hud_mdns_timeout_desc,
        500..30000, 500, get = { it.hudMdnsTimeoutMs }, set = { s, v -> s.copy(hudMdnsTimeoutMs = v) }),
    AdvancedSpec("hudDiscoverySprintMs", AdvGroup.HUD, R.string.adv_hud_discovery_sprint, R.string.adv_hud_discovery_sprint_desc,
        1000..120000, 1000, get = { it.hudDiscoverySprintMs }, set = { s, v -> s.copy(hudDiscoverySprintMs = v) }),
    AdvancedSpec("hudDiscoveryTotalTimeoutMs", AdvGroup.HUD, R.string.adv_hud_discovery_total, R.string.adv_hud_discovery_total_desc,
        5000..60000, 1000, get = { it.hudDiscoveryTotalTimeoutMs }, set = { s, v -> s.copy(hudDiscoveryTotalTimeoutMs = v) }),
    AdvancedSpec("hudUdpProbeTimeoutMs", AdvGroup.HUD, R.string.adv_hud_udp_probe, R.string.adv_hud_udp_probe_desc,
        3000..30000, 500, get = { it.hudUdpProbeTimeoutMs }, set = { s, v -> s.copy(hudUdpProbeTimeoutMs = v) }),
    AdvancedSpec("hudUdpBeaconFreshnessMs", AdvGroup.HUD, R.string.adv_hud_udp_freshness, R.string.adv_hud_udp_freshness_desc,
        3000..30000, 500, get = { it.hudUdpBeaconFreshnessMs }, set = { s, v -> s.copy(hudUdpBeaconFreshnessMs = v) }),
    AdvancedSpec("hudUdpPollIntervalMs", AdvGroup.HUD, R.string.adv_hud_udp_poll, R.string.adv_hud_udp_poll_desc,
        50..1000, 50, get = { it.hudUdpPollIntervalMs }, set = { s, v -> s.copy(hudUdpPollIntervalMs = v) }),
    AdvancedSpec("hudManualHintDelayMs", AdvGroup.HUD, R.string.adv_hud_manual_grace, R.string.adv_hud_manual_grace_desc,
        0..5000, 100, get = { it.hudManualHintDelayMs }, set = { s, v -> s.copy(hudManualHintDelayMs = v) }),
    AdvancedSpec("hudMdnsServiceInfoTimeoutMs", AdvGroup.HUD, R.string.adv_hud_mdns_resolve, R.string.adv_hud_mdns_resolve_desc,
        200..5000, 100, get = { it.hudMdnsServiceInfoTimeoutMs }, set = { s, v -> s.copy(hudMdnsServiceInfoTimeoutMs = v) }),

    // --- Auto-lights ---
    AdvancedSpec("autoLightNoGpsRetryMs", AdvGroup.AUTOLIGHTS, R.string.adv_autolight_no_gps_retry, R.string.adv_autolight_no_gps_retry_desc,
        250..30000, 250, get = { it.autoLightNoGpsRetryMs }, set = { s, v -> s.copy(autoLightNoGpsRetryMs = v) }),
    AdvancedSpec("autoToggleGraceMs", AdvGroup.AUTOLIGHTS, R.string.adv_autolight_toggle_grace, R.string.adv_autolight_toggle_grace_desc,
        250..30000, 250, get = { it.autoToggleGraceMs }, set = { s, v -> s.copy(autoToggleGraceMs = v) }),

    // --- Navigation behaviour ---
    AdvancedSpec("navMovingKmh", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_moving, R.string.adv_nav_moving_desc,
        1..50, 1, unit = "km/h", get = { it.navMovingKmh }, set = { s, v -> s.copy(navMovingKmh = v) }),
    AdvancedSpec("navPrepareDistM", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_prepare_dist, R.string.adv_nav_prepare_dist_desc,
        20..2000, 10, unit = "m", get = { it.navPrepareDistM }, set = { s, v -> s.copy(navPrepareDistM = v) }),
    AdvancedSpec("navExecuteDistM", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_execute_dist, R.string.adv_nav_execute_dist_desc,
        5..500, 5, unit = "m", get = { it.navExecuteDistM }, set = { s, v -> s.copy(navExecuteDistM = v) }),
    AdvancedSpec("navProxBandM", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_prox_band, R.string.adv_nav_prox_band_desc,
        1..100, 1, unit = "m", get = { it.navProxBandM }, set = { s, v -> s.copy(navProxBandM = v) }),
    AdvancedSpec("navMinInterStopMoveM", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_inter_stop, R.string.adv_nav_inter_stop_desc,
        5..500, 5, unit = "m", get = { it.navMinInterStopMoveM }, set = { s, v -> s.copy(navMinInterStopMoveM = v) }),

    // --- Radar classification ---
    AdvancedSpec("radarFastApproachDistM", AdvGroup.RADAR_CLASS, R.string.adv_radar_fast_dist, R.string.adv_radar_fast_dist_desc,
        5..500, 5, unit = "m", get = { it.radarFastApproachDistM }, set = { s, v -> s.copy(radarFastApproachDistM = v) }),
    AdvancedSpec("radarFastApproachSpeedKmh", AdvGroup.RADAR_CLASS, R.string.adv_radar_fast_speed, R.string.adv_radar_fast_speed_desc,
        5..200, 5, unit = "km/h", get = { it.radarFastApproachSpeedKmh }, set = { s, v -> s.copy(radarFastApproachSpeedKmh = v) }),
    AdvancedSpec("radarStaticTargetKmh", AdvGroup.RADAR_CLASS, R.string.adv_radar_static, R.string.adv_radar_static_desc,
        1..50, 1, unit = "km/h", get = { it.radarStaticTargetKmh }, set = { s, v -> s.copy(radarStaticTargetKmh = v) }),
    AdvancedSpec("radarFallbackClosingMps", AdvGroup.RADAR_CLASS, R.string.adv_radar_fallback, R.string.adv_radar_fallback_desc,
        1..100, 1, unit = "m/s", get = { it.radarFallbackClosingMps }, set = { s, v -> s.copy(radarFallbackClosingMps = v) }),
    AdvancedSpec("radarMinFrameRateMs", AdvGroup.RADAR_CLASS, R.string.adv_radar_min_frame, R.string.adv_radar_min_frame_desc,
        20..5000, 10, get = { it.radarMinFrameRateMs }, set = { s, v -> s.copy(radarMinFrameRateMs = v) }),

    // --- Charging ETA ---
    AdvancedSpec("chargingTargetPercent", AdvGroup.CHARGING, R.string.adv_charging_target_pct, R.string.adv_charging_target_pct_desc,
        50..99, 1, unit = "%", get = { it.chargingTargetPercent }, set = { s, v -> s.copy(chargingTargetPercent = v) }),
    AdvancedSpec("chargingTargetTaperX100", AdvGroup.CHARGING, R.string.adv_charging_target_taper, R.string.adv_charging_target_taper_desc,
        100..300, 5, unit = "x", get = { it.chargingTargetTaperX100 }, set = { s, v -> s.copy(chargingTargetTaperX100 = v) },
        format = taperFormat, parse = taperParse, allowSign = true),
    AdvancedSpec("chargingCvTaperX100", AdvGroup.CHARGING, R.string.adv_charging_cv_taper, R.string.adv_charging_cv_taper_desc,
        100..500, 5, unit = "x", get = { it.chargingCvTaperX100 }, set = { s, v -> s.copy(chargingCvTaperX100 = v) },
        format = taperFormat, parse = taperParse, allowSign = true),
    AdvancedSpec("chargingWarmupMinPercentGain", AdvGroup.CHARGING, R.string.adv_charging_warmup_gain, R.string.adv_charging_warmup_gain_desc,
        1..50, 1, unit = "%", get = { it.chargingWarmupMinPercentGain }, set = { s, v -> s.copy(chargingWarmupMinPercentGain = v) }),
    AdvancedSpec("chargingWarmupMinDurationMs", AdvGroup.CHARGING, R.string.adv_charging_warmup_dur, R.string.adv_charging_warmup_dur_desc,
        5000..600000, 5000, get = { it.chargingWarmupMinDurationMs }, set = { s, v -> s.copy(chargingWarmupMinDurationMs = v) }),
    AdvancedSpec("chargingWindowMs", AdvGroup.CHARGING, R.string.adv_charging_window, R.string.adv_charging_window_desc,
        30000..1200000, 30000, get = { it.chargingWindowMs }, set = { s, v -> s.copy(chargingWindowMs = v) }),
    AdvancedSpec("chargingSanityCapMinutes", AdvGroup.CHARGING, R.string.adv_charging_sanity_cap, R.string.adv_charging_sanity_cap_desc,
        60..1440, 30, unit = "min", get = { it.chargingSanityCapMinutes }, set = { s, v -> s.copy(chargingSanityCapMinutes = v) }),
    AdvancedSpec("chargingMedianFilterSize", AdvGroup.CHARGING, R.string.adv_charging_median, R.string.adv_charging_median_desc,
        1..21, 2, unit = "", get = { it.chargingMedianFilterSize }, set = { s, v -> s.copy(chargingMedianFilterSize = v) }),

    // --- Screen geometry variables ---
    AdvancedSpec("compactMaxScreenDp", AdvGroup.GEOMETRY, R.string.adv_compact_max_screen, R.string.adv_compact_max_screen_desc,
        300..800, 10, unit = "dp", get = { it.compactMaxScreenDp }, set = { s, v -> s.copy(compactMaxScreenDp = v) }),
    AdvancedSpec("coverCutoutInsetDp", AdvGroup.GEOMETRY, R.string.adv_cover_cutout_inset, R.string.adv_cover_cutout_inset_desc,
        48..200, 4, unit = "dp", get = { it.coverCutoutInsetDp }, set = { s, v -> s.copy(coverCutoutInsetDp = v) }),
    AdvancedSpec("simpleSpeedoScalePct", AdvGroup.GEOMETRY, R.string.adv_simple_speedo_scale, R.string.adv_simple_speedo_scale_desc,
        40..90, 2, unit = "%", get = { it.simpleSpeedoScalePct }, set = { s, v -> s.copy(simpleSpeedoScalePct = v) }),
    AdvancedSpec("navSidebarWidthDp", AdvGroup.GEOMETRY, R.string.adv_nav_sidebar_width, R.string.adv_nav_sidebar_width_desc,
        240..480, 10, unit = "dp", get = { it.navSidebarWidthDp }, set = { s, v -> s.copy(navSidebarWidthDp = v) }),
    AdvancedSpec("navSidebarMinScreenDp", AdvGroup.GEOMETRY, R.string.adv_nav_sidebar_min, R.string.adv_nav_sidebar_min_desc,
        400..900, 20, unit = "dp", get = { it.navSidebarMinScreenDp }, set = { s, v -> s.copy(navSidebarMinScreenDp = v) }),
)
