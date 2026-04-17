package com.eried.evendarkerbot.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,

    // Connection
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val autoConnect: Boolean = true,

    // Speed settings (sent to wheel)
    val tiltbackSpeedKmh: Float = 50f,
    val alarmSpeedKmh: Float = 40f,

    // Safety speed (applied when safety toggle is ON)
    val safetyTiltbackKmh: Float = 25f,
    val safetyAlarmKmh: Float = 20f,

    // Stores wheel's current speeds so we can restore them when safety is OFF
    val normalTiltbackKmh: Float = 50f,
    val normalBeepKmh: Float = 40f,

    // Voice
    val voiceEnabled: Boolean = true,
    val voiceIntervalSeconds: Int = 30,
    val voiceSpeechRate: Float = 1.2f,
    val voiceLocale: String = "en_US",  // locale tag for TTS voice
    // Periodic voice report toggles
    val voiceReportSpeed: Boolean = true,
    val voiceReportBattery: Boolean = true,
    val voiceReportTemp: Boolean = false,
    val voiceReportPwm: Boolean = false,
    val voiceReportDistance: Boolean = false,
    // On-trigger (manual/flic) voice report toggles
    val triggerReportSpeed: Boolean = true,
    val triggerReportBattery: Boolean = true,
    val triggerReportTemp: Boolean = true,
    val triggerReportPwm: Boolean = true,
    val triggerReportDistance: Boolean = true,

    // Voice report: include recording state
    val voiceReportRecording: Boolean = false,
    val triggerReportRecording: Boolean = true,

    // Voice report item order (comma-separated: Speed,Battery,Temp,PWM,Distance,Recording)
    val voiceReportOrder: String = "Speed,Battery,Temp,PWM,Distance,Recording",

    // Special announcements (event-driven)
    val announceWheelLock: Boolean = true,
    val announceLights: Boolean = true,
    val announceRecording: Boolean = true,
    val announceConnection: Boolean = true,
    val announceGps: Boolean = true,
    val announceSafetyMode: Boolean = true,

    // Recording
    val autoRecord: Boolean = false,

    // Flic button 1
    val flic1Address: String? = null,
    val flic1Name: String = "Button 1",
    val flic1Click: String = "HORN",
    val flic1DoubleClick: String = "LIGHT_TOGGLE",
    val flic1Hold: String = "SAFETY_TOGGLE",

    // Flic button 2
    val flic2Address: String? = null,
    val flic2Name: String = "Button 2",
    val flic2Click: String = "VOICE_ANNOUNCE",
    val flic2DoubleClick: String = "RECORD_TOGGLE",
    val flic2Hold: String = "LOCK_TOGGLE",

    // Auto-lights (sunset/sunrise based, uses live GPS from trip repository)
    val autoLightsEnabled: Boolean = false,
    val autoLightsOnMinutesBefore: Int = 30,   // minutes before sunset to turn lights ON
    val autoLightsOffMinutesAfter: Int = 30,   // minutes after sunrise to turn lights OFF

    // Auto-volume (speed-based, 3 control points: 0, 40, 80 km/h)
    val autoVolumeEnabled: Boolean = false,
    val autoVolumeCurve: String = "0:20,40:60,80:100",

    // Display units
    val imperialUnits: Boolean = false,

    // Volume keys (work while app is in foreground)
    val volumeKeysEnabled: Boolean = false,
    val volumeUpClick: String = "HORN",
    val volumeUpHold: String = "VOICE_ANNOUNCE",
    val volumeDownClick: String = "LIGHT_TOGGLE",
    val volumeDownHold: String = "SAFETY_TOGGLE"
)

enum class FlicAction(val label: String) {
    NONE("None"),
    HORN("Horn"),
    LIGHT_TOGGLE("Toggle Light"),
    LOCK_TOGGLE("Lock Wheel"),
    SAFETY_TOGGLE("Toggle Legal Mode"),
    SAFETY_ON("Legal Mode ON"),
    SAFETY_OFF("Legal Mode OFF"),
    VOICE_ANNOUNCE("Voice Report"),
    RECORD_TOGGLE("Toggle Recording"),
    MEDIA_PLAY_PAUSE("Play / Pause Music"),
    MEDIA_NEXT("Next Song"),
    MEDIA_PREVIOUS("Previous Song")
}
