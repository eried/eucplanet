package com.eried.eucplanet.data.model

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
    val tiltbackKmh: Float = 45f,
    val alarmKmh: Float = 38f,
    val legalTiltbackKmh: Float = 25f,
    val legalAlarmKmh: Float = 22f,

    // Voice / TTS
    val ttsEnabled: Boolean = true,
    val speechRate: Float = 50f,
    val announceIntervalSec: Int = 60,
    val announceSpeed: Boolean = true,
    val announceBattery: Boolean = true,
    val announceTemp: Boolean = false,
    // Spoken events (fired on the action, not periodically)
    val announceLights: Boolean = false,
    val announceWheelLock: Boolean = false,
    val announceLegalMode: Boolean = false,
    val announceRecording: Boolean = false,

    // Display
    val theme: Int = 1,          // 0 = Light, 1 = Dark, 2 = Pure Black
    val accent: Int = 0,
    val gaugeColorBand: Boolean = true,

    // Units (display only — stored telemetry is always metric: km/h, km, °C)
    val unitSpeed: String = "kmh",   // kmh | mph | ms | kn
    val unitDistance: String = "km", // km | mi | m | ft | mil
    val unitTemp: String = "C",      // C | F | K

    // Alarms
    val speedAlarmEnabled: Boolean = true,
    val speedAlarmKmh: Float = 45f,
    val tempAlarmEnabled: Boolean = true,
    val tempAlarmC: Float = 65f,
    val currentAlarmEnabled: Boolean = false,
    val currentAlarmA: Float = 60f,
    val pwmAlarmEnabled: Boolean = true,
    val pwmAlarmPct: Float = 80f,

    // Automations
    val autoLights: Boolean = false,
    val autoLightsSpeedKmh: Float = 5f,
    val autoVolume: Boolean = false,

    // Motor sound (engine synthesis)
    val engineSound: Boolean = false,
    val engineVolume: Float = 50f,

    // Location / sensors
    val externalGpsPriority: Boolean = false,
    val showGpsOnDashboard: Boolean = true,

    // General
    val autoConnectLastWheel: Boolean = true,
    val lastWheelAddress: String = "", // remembered for auto-reconnect (device id)
    val keepScreenOn: Boolean = true,
    val autoStartRecording: Boolean = false,
    val backButtonExits: Boolean = false,

    // Dashboard
    val dashboardColumns: Int = 2,
    val statCorners: Boolean = true,

    // Cloud / backup
    val cloudSyncSettings: Boolean = false,
    val autoBackupTrips: Boolean = false,

    // Navigator
    val navVoiceGuidance: Boolean = true,

    // Integration
    val flicEnabled: Boolean = false,
    val volumeKeyControls: Boolean = false,
    val radarEnabled: Boolean = false,

    // Watch
    val watchKeepOn: Boolean = true,
    val watchAutoStart: Boolean = false,
)
