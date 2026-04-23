package com.eried.eucplanet.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eried.eucplanet.R

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,

    // Connection
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val autoConnect: Boolean = true,

    // Speed settings (sent to wheel) - the "normal" mode values
    val tiltbackSpeedKmh: Float = 50f,
    val alarmSpeedKmh: Float = 40f,

    // Legal-mode speed (applied when legal toggle is ON)
    val safetyTiltbackKmh: Float = 20f,
    val safetyAlarmKmh: Float = 18f,

    // Voice
    val voiceEnabled: Boolean = true,
    // Independent toggle for the periodic (every N seconds) status announcements. When false,
    // voice still works for triggered events (manual button, Flic, alarms) but the periodic
    // loop is silent. Toggled from the dashboard via long-press on the Voice action.
    @ColumnInfo(defaultValue = "1")
    val voicePeriodicEnabled: Boolean = true,
    val voiceOnlyWhenConnected: Boolean = true,
    @ColumnInfo(defaultValue = "60")
    val voiceIntervalSeconds: Int = 60,
    val voiceSpeechRate: Float = 1.2f,
    val voiceLocale: String = "en_US",  // locale tag for TTS voice
    // Audio focus behavior while speaking: "DUCK" (lower other), "PAUSE" (pause other), "OFF" (no focus)
    val voiceAudioFocus: String = "DUCK",
    // Where to route the voice: "MEDIA" (music slider), "NOTIFICATION" (ring slider), "ALARM" (alarm slider, loudest)
    val voiceOutputChannel: String = "MEDIA",
    // Periodic voice report toggles
    val voiceReportSpeed: Boolean = true,
    val voiceReportBattery: Boolean = true,
    val voiceReportTemp: Boolean = false,
    val voiceReportPwm: Boolean = false,
    val voiceReportDistance: Boolean = false,
    val voiceReportTime: Boolean = false,
    // On-trigger (manual/flic) voice report toggles
    val triggerReportSpeed: Boolean = true,
    val triggerReportBattery: Boolean = true,
    val triggerReportTemp: Boolean = true,
    val triggerReportPwm: Boolean = false,
    val triggerReportDistance: Boolean = true,
    val triggerReportTime: Boolean = true,

    // Voice report: include recording state
    val voiceReportRecording: Boolean = false,
    val triggerReportRecording: Boolean = true,

    // Voice report item order (comma-separated: Speed,Battery,Time,Temp,PWM,Distance,Recording)
    val voiceReportOrder: String = "Speed,Battery,Time,Temp,PWM,Distance,Recording",

    // Special announcements (event-driven)
    val announceWheelLock: Boolean = true,
    val announceLights: Boolean = true,
    val announceRecording: Boolean = true,
    val announceConnection: Boolean = true,
    val announceGps: Boolean = true,
    val announceSafetyMode: Boolean = true,
    val announceWelcome: Boolean = true,

    // Recording
    val autoRecord: Boolean = true,
    // Motion-linked loop: wait for speed > 0 to start recording, auto-stop after idle timeout,
    // restart on next motion. When false, recording starts at connect and runs until disconnect.
    @ColumnInfo(defaultValue = "1")
    val autoRecordStartInMotion: Boolean = true,
    @ColumnInfo(defaultValue = "180")
    val autoRecordStopIdleSeconds: Int = 180,

    // Flic button 1
    val flic1Address: String? = null,
    val flic1Name: String = "Button 1",
    val flic1Click: String = "VOICE_ANNOUNCE",
    val flic1DoubleClick: String = "HORN",
    val flic1Hold: String = "LIGHT_TOGGLE",

    // Flic button 2
    val flic2Address: String? = null,
    val flic2Name: String = "Button 2",
    val flic2Click: String = "NONE",
    val flic2DoubleClick: String = "NONE",
    val flic2Hold: String = "SAFETY_ON",

    // Flic button 3
    @ColumnInfo(defaultValue = "NULL")
    val flic3Address: String? = null,
    @ColumnInfo(defaultValue = "Button 3")
    val flic3Name: String = "Button 3",
    @ColumnInfo(defaultValue = "NONE")
    val flic3Click: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val flic3DoubleClick: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val flic3Hold: String = "NONE",

    // Flic button 4
    @ColumnInfo(defaultValue = "NULL")
    val flic4Address: String? = null,
    @ColumnInfo(defaultValue = "Button 4")
    val flic4Name: String = "Button 4",
    @ColumnInfo(defaultValue = "NONE")
    val flic4Click: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val flic4DoubleClick: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val flic4Hold: String = "NONE",

    // Auto-lights (sunset/sunrise based, uses live GPS from trip repository)
    val autoLightsEnabled: Boolean = false,
    val autoLightsOnMinutesBefore: Int = 30,   // minutes before sunset to turn lights ON
    val autoLightsOffMinutesAfter: Int = 30,   // minutes after sunrise to turn lights OFF

    // Auto-volume (speed-based, 4 control points: 0, 25, 50, 75 km/h, monotonic ascending)
    val autoVolumeEnabled: Boolean = false,
    val autoVolumeCurve: String = "0:20,25:50,50:80,75:100",

    // Display units
    val imperialUnits: Boolean = false,

    // Volume keys (work while app is in foreground)
    val volumeKeysEnabled: Boolean = false,
    val volumeUpClick: String = "HORN",
    val volumeUpHold: String = "VOICE_ANNOUNCE",
    val volumeDownClick: String = "LIGHT_TOGGLE",
    val volumeDownHold: String = "SAFETY_TOGGLE",

    // Appearance
    // language: BCP-47 tag. "en", "es", "ru", "no", "de". Stored now, wired in Stage B.
    val language: String = "en",
    // themeMode: "black", "dark", "light", "system"
    val themeMode: String = "black",
    // accentColor: key into the accent palette
    val accentColor: String = "default",
    // Colored danger-zone band behind the speed arc (yellow/orange/red thresholds).
    @ColumnInfo(defaultValue = "0")
    val showGaugeColorBand: Boolean = false,
    // Percentages of the full speed sweep where orange and red zones begin (yellow fills below orange).
    @ColumnInfo(defaultValue = "65")
    val gaugeOrangeThresholdPct: Int = 65,
    @ColumnInfo(defaultValue = "85")
    val gaugeRedThresholdPct: Int = 85,
    // Haptic feedback on dashboard action button taps.
    @ColumnInfo(defaultValue = "1")
    val hapticFeedback: Boolean = true,
    // "AMPS" or "WATTS" — long-press the amps card to switch.
    @ColumnInfo(defaultValue = "AMPS")
    val currentDisplayMode: String = "AMPS",

    // Backup folder (SAF tree URI on local storage; companion sync app handles cloud upload)
    val syncFolderUri: String? = null,
    val lastSettingsBackupAt: Long? = null
)

enum class FlicAction(val labelRes: Int) {
    NONE(R.string.flic_action_none),
    HORN(R.string.flic_action_horn),
    LIGHT_TOGGLE(R.string.flic_action_light),
    LOCK_TOGGLE(R.string.flic_action_lock),
    SAFETY_TOGGLE(R.string.flic_action_legal_toggle),
    SAFETY_ON(R.string.flic_action_legal_on),
    SAFETY_OFF(R.string.flic_action_legal_off),
    VOICE_ANNOUNCE(R.string.flic_action_voice),
    RECORD_TOGGLE(R.string.flic_action_record),
    MEDIA_PLAY_PAUSE(R.string.flic_action_media_play),
    MEDIA_NEXT(R.string.flic_action_media_next),
    MEDIA_PREVIOUS(R.string.flic_action_media_prev)
}
