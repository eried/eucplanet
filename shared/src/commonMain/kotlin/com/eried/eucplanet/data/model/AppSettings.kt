package com.eried.eucplanet.data.model

import com.eried.eucplanet.data.AlarmRule
import com.eried.eucplanet.data.defaultAlarmRules
import kotlinx.serialization.Serializable

/**
 * The shared app-settings model — the v1 "core ride slice" subset of the Android
 * `AppSettings` (which has 200+ fields). Plain immutable data so it serializes
 * cleanly later (kotlinx.serialization, replacing the Android org.json store) and
 * drives both the Settings UI and the alarm/automation logic. Add fields as more
 * of the Android surface is ported.
 */
@Serializable
data class AppSettings(
    // Speed
    val speedCalibrationPct: Float = 0f,   // -15..+15 % offset applied to wheel speed
    val tiltbackKmh: Float = 45f,
    val alarmKmh: Float = 38f,
    val legalTiltbackKmh: Float = 25f,
    val legalAlarmKmh: Float = 22f,

    // How the dashboard top bar names the connected wheel.
    val wheelNameDisplay: String = "MODEL",   // MODEL | BRAND | NONE
    // Current/load tile shows amperage or power (long-press the tile to toggle).
    val currentDisplayMode: String = "AMPS",  // AMPS | WATTS
    // Session alarm mute (persists across restarts).
    val alarmsMuted: Boolean = false,

    // Voice / TTS
    val ttsEnabled: Boolean = true,
    val voiceId: String = "",        // selected system-voice identifier ("" = default)
    val speechRate: Float = 1.1f,    // speed multiplier, 1.0 = normal (matches Android's voiceSpeechRate)
    val voicePeriodicEnabled: Boolean = false, // periodic status announcements (vs only event/manual)
    val voiceAnnounceWhen: String = "RIDING",  // ALWAYS | CONNECTED | RIDING — when periodic may speak
    val announceIntervalSec: Int = 60,
    val announceSpeed: Boolean = true,
    val announceBattery: Boolean = true,
    val announceTemp: Boolean = false,
    // Spoken events (fired on the action, not periodically)
    val announceLights: Boolean = false,
    val announceWheelLock: Boolean = false,
    val announceLegalMode: Boolean = false,
    val announceRecording: Boolean = false,
    val announceConnection: Boolean = false,  // "Wheel connected" / "Wheel disconnected" — off until the rider opts in
    val announceWelcome: Boolean = false,     // spoken once on app launch — off by default (silent fresh install)
    val announceGps: Boolean = false,         // "GPS signal acquired" / "lost"

    // Automations (sun + GPS based, like Android)
    val autoLightsEnabled: Boolean = false,
    val autoLightsOnMinutesBefore: Int = 30,  // minutes before sunset to turn lights ON
    val autoLightsOffMinutesAfter: Int = 30,  // minutes after sunrise to turn lights OFF

    // First-launch welcome tour (shown once; matches Android's welcomeTutorialSeen).
    val welcomeTutorialSeen: Boolean = false,

    // Display
    val theme: Int = 1,          // 0 = Light, 1 = Dark, 2 = Pure Black
    val accent: Int = 0,
    val gaugeColorBand: Boolean = true,
    val gaugeOrangeThresholdPct: Int = 65,  // gauge arc goes orange at this % of the dial
    val gaugeRedThresholdPct: Int = 85,      // ...and red at this %
    // Theme editor: per-token color overrides on top of the selected built-in.
    val customThemeEnabled: Boolean = false,
    val customThemeColors: Map<String, Int> = emptyMap(), // ThemeTokenSpec.key -> ARGB Int

    // Units (display only — stored telemetry is always metric: km/h, km, °C)
    val unitSpeed: String = "kmh",   // kmh | mph | ms | kn
    val unitDistance: String = "km", // km | mi | m | ft | mil
    val unitTemp: String = "C",      // C | F | K

    // Alarms — a customizable rule list (ported from Android's AlarmRule engine).
    val alarmRules: List<AlarmRule> = defaultAlarmRules,

    // Automations
    val autoLights: Boolean = false,
    val autoLightsSpeedKmh: Float = 5f,
    val autoVolume: Boolean = false,

    // Charging monitor (matches Android)
    val chargingAutoOpen: Boolean = true,       // open the Battery screen when charging starts
    val chargingEstimateToFull: Boolean = false,// predict to 100% instead of 80%
    val chargingDashboardIcon: Boolean = true,  // show a charging bolt on the dashboard

    // Location / sensors
    val externalGpsPriority: Boolean = false,
    val showGpsOnDashboard: Boolean = true,

    // General
    val autoConnectLastWheel: Boolean = true,
    val lastWheelAddress: String = "", // remembered for auto-reconnect (device id)
    val keepScreenOn: Boolean = true,
    val autoStartRecording: Boolean = true,           // master auto-record (Android: autoRecord)
    val autoRecordStartInMotion: Boolean = true,       // start on first motion vs on connect
    val autoRecordStopIdleSeconds: Int = 180,          // auto-stop after this many idle seconds
    val backButtonExits: Boolean = false,

    // Dashboard
    val dashboardColumns: Int = 2,
    val statCorners: Boolean = true,
    // Ordered, visible metric-tile + action-button keys (CSV) — drag/hide editor.
    val dashboardMetricOrder: String = "battery,temp,voltage,current,load,trip",
    val dashboardActionOrder: String = "horn,light,voice,legal,lock,rec",
    // User-defined raw BLE commands (fired from Service Mode).
    val customBleCommands: List<CustomBleCommand> = emptyList(),

    // External GPS (RaceBox, on its own BLE central — Android parity)
    val externalGpsEnabled: Boolean = false,
    val externalGpsAddress: String = "",
    val externalGpsName: String = "",
    val gpsPrioritizeExternal: Boolean = true,   // use the box's speed over the wheel's
    val gpsShowOnDashboard: Boolean = true,

    // Rear-view radar (Garmin Varia, on its own BLE central)
    val radarEnabled: Boolean = false,
    val radarAddress: String = "",
    val radarName: String = "",
    val radarShowOverlay: Boolean = true,
    val radarOverlayLeft: Boolean = false,       // overlay edge: false = right, true = left

    // Cloud / backup
    val cloudSyncSettings: Boolean = false,
    // Dropbox backup (OAuth tokens + linked-account label)
    val dropboxAccessToken: String = "",
    val dropboxRefreshToken: String = "",
    val dropboxAccessTokenExpiresAt: Long = 0L,
    val dropboxAccountLabel: String = "",
    val dropboxAutoBackup: Boolean = true,   // upload trips/settings on save when linked
    val autoBackupTrips: Boolean = false,

    // EucStats online — trip backup + leaderboards (dev: dev.eucstats.ried.no).
    val eucStatsEnabled: Boolean = false,
    val eucStatsStoreId: String = "",      // client-generated UUID, persisted once
    val eucStatsDisplayName: String = "",
    val eucStatsFlag: String = "",         // ISO country flag code (optional)
    val eucStatsAutoUpload: Boolean = true,

    // Navigator
    val navVoiceGuidance: Boolean = true,
    val navArrivalRadiusM: Int = 25,
    val navOffRouteToleranceM: Int = 40,
    val navSolveFullPath: Boolean = true,
    val navRouterUrl: String = "https://routing.openstreetmap.de",
    val navGeocoderUrl: String = "https://nominatim.openstreetmap.org/search",
    val navDefaultTravelMode: String = "CYCLING", // CYCLING | DRIVING | WALKING | STRAIGHT
    val navMapType: String = "DARK",              // DARK | LIGHT | SATELLITE
    val navHomeJson: String = "",
    val navWorkJson: String = "",

    // Motor (synthesized engine sound) — fields/defaults mirror Android.
    val engineSoundEnabled: Boolean = false,
    val engineType: String = "FOUR_STROKE_SINGLE",
    val engineVolume: Float = 0.6f,
    val engineVolumeAutoEnabled: Boolean = false,
    val engineVolumeAutoCurve: String = "0:1.00,25:0.10,50:0.10,75:0.00",
    val engineMuffler: String = "HALF",      // OPEN | HALF | MUFFLED
    val engineGearbox: String = "FOUR",      // OFF | FOUR | SIX
    val engineIdleBehavior: String = "FADE", // ALWAYS | FADE | MOVING
    val engineDecelChar: String = "STANDARD",// SMOOTH | STANDARD | BACKFIRE
    val engineBrake: String = "LIGHT",       // OFF | LIGHT | STRONG
    val engineDuckOnVoice: String = "DUCK",  // DUCK | PAUSE | MIX
    val engineHeadphonesOnly: Boolean = false,

    // Integration
    val flicEnabled: Boolean = false,
    val volumeKeyControls: Boolean = false,

    // Watch (Apple Watch dial — the Wear-only STEM/volume bindings don't apply)
    val watchKeepOn: Boolean = true,
    val watchAutoStart: Boolean = false,
    val watchShowWheelBattery: Boolean = true,   // battery ring on the dial
    val watchShowPwm: Boolean = true,            // PWM % under the speed
    val watchShowSpeedUnit: Boolean = true,      // km/h / mph label
    val watchDialRotationDeg: Int = 0,           // rotate the whole dial (wrist mount)
    val watchHapticOnAction: Boolean = true,     // tap feedback on horn/light

    // HUD (external heads-up display — phone dials ws://<ip>:<port>/state)
    val hudEnabled: Boolean = false,
    val hudIp: String = "",
    val hudPort: Int = 28080,
    /** Overlay Studio preset JSON streamed as the HUD's "Custom" screen ("" = none). */
    val hudCustomOverlayJson: String = "",
)
