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

    // Speed-based volume boost. Multiplier curve maps speed to 1×–2× of the user's baseline volume.
    // 1× = no boost (baseline), 2× = double the baseline (capped at 100% by the system).
    // 4 control points at 0/25/50/75 km/h. 0 km/h is locked at 1× (no boost at standstill).
    // Baseline starts at -1 (uninitialized) and is captured from the system music volume on first
    // tick after enable. Manual volume changes during motion rebase: baseline = manual / multiplier.
    val autoVolumeEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0:1.0,25:1.0,50:1.5,75:2.0")
    val autoVolumeCurve: String = "0:1.0,25:1.0,50:1.5,75:2.0",
    @ColumnInfo(defaultValue = "-1")
    val autoVolumeBaselinePercent: Int = -1,

    // Display units
    val imperialUnits: Boolean = false,

    // Volume keys (work while app is in foreground)
    val volumeKeysEnabled: Boolean = false,
    val volumeUpClick: String = "HORN",
    val volumeUpHold: String = "VOICE_ANNOUNCE",
    val volumeDownClick: String = "LIGHT_TOGGLE",
    val volumeDownHold: String = "SAFETY_TOGGLE",

    // Appearance
    // language: BCP-47 tag (e.g. "en", "es", "es-419", "no", "pt-BR"). Empty string
    // means "not set yet" — MainActivity picks a default from the system locale on
    // first launch and persists the choice.
    val language: String = "",
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
    val lastSettingsBackupAt: Long? = null,

    // --- Wear OS companion (only takes effect when a Wear OS watch is paired) ---
    @ColumnInfo(defaultValue = "1")
    val watchKeepScreenOn: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val watchAutoStart: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val watchShowWheelBattery: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val watchShowPhoneBattery: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val watchShowWatchBattery: Boolean = true,
    /** "BAR", "NUMBERS", or "BOTH". */
    @ColumnInfo(defaultValue = "BOTH")
    val watchPwmDisplay: String = "BOTH",
    @ColumnInfo(defaultValue = "1")
    val watchShowSpeedUnit: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val watchEnableGpsSpeed: Boolean = false,

    /**
     * Hardware-button bindings on the watch (Galaxy Watch Ultra exposes the
     * orange Action button as STEM_1 and the bottom side button as STEM_2;
     * Pixel Watch only has one). Stored as the [FlicAction] enum name so the
     * picker can reuse the same UI/string set as Flic and Volume keys. The
     * Wear OS side reads these via the Data Layer publish, intercepts
     * KEYCODE_STEM_* in MainActivity, and either fires a local control
     * intent or routes to the phone over /euc/control.
     */
    @ColumnInfo(defaultValue = "NONE")
    val watchStem1Click: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val watchStem1Hold: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val watchStem2Click: String = "NONE",
    @ColumnInfo(defaultValue = "NONE")
    val watchStem2Hold: String = "NONE",

    /**
     * On-screen watch button bindings. Two configurable buttons; tap fires the
     * "click" action, long-press fires the "hold" action. Same FlicAction
     * vocabulary as Flic / Volume / Stem buttons. Defaults match the wheel's
     * most-used controls (Horn, Light) so out-of-the-box behavior matches
     * the previous hardcoded buttons.
     */
    @ColumnInfo(defaultValue = "HORN")
    val watchScreen1Click: String = "HORN",
    @ColumnInfo(defaultValue = "NONE")
    val watchScreen1Hold: String = "NONE",
    @ColumnInfo(defaultValue = "LIGHT_TOGGLE")
    val watchScreen2Click: String = "LIGHT_TOGGLE",
    @ColumnInfo(defaultValue = "NONE")
    val watchScreen2Hold: String = "NONE",

    /**
     * If true, the watch vibrates briefly whenever a button-bound action
     * fires (tap or hold) so the user gets tactile confirmation.
     */
    @ColumnInfo(defaultValue = "1")
    val watchHapticOnAction: Boolean = true,

    // --- Motor Sound generator ---
    //
    // Synthesises a virtual engine driven by live (speed, pwm) telemetry. Goes
    // through the media stream so it mixes with music; the user controls how it
    // behaves under voice announces via [engineDuckOnVoice].
    @ColumnInfo(defaultValue = "0")
    val engineSoundEnabled: Boolean = false,
    /** Preset key. See [com.eried.eucplanet.audio.EngineProfile.PROFILES]. */
    @ColumnInfo(defaultValue = "FOUR_STROKE_SINGLE")
    val engineType: String = "FOUR_STROKE_SINGLE",
    /** In-app gain 0..1 over the media stream. */
    @ColumnInfo(defaultValue = "0.6")
    val engineVolume: Float = 0.6f,
    /** "OPEN", "HALF", "MUFFLED" — controls high-harmonic rolloff. */
    @ColumnInfo(defaultValue = "HALF")
    val engineMuffler: String = "HALF",
    /** "OFF", "FOUR", "SIX". Ignored for engines whose profile is gearless (synth/futuristic). */
    @ColumnInfo(defaultValue = "FOUR")
    val engineGearbox: String = "FOUR",
    /** "ALWAYS" (always idling when connected), "FADE" (fade after parked), "MOVING" (only when moving). */
    @ColumnInfo(defaultValue = "FADE")
    val engineIdleBehavior: String = "FADE",
    /** "SMOOTH" (no pops), "STANDARD", "BACKFIRE" (heavy pops on decel). */
    @ColumnInfo(defaultValue = "STANDARD")
    val engineDecelChar: String = "STANDARD",
    /** "OFF", "LIGHT", "STRONG" — engine-brake whine layered during sustained decel/regen. */
    @ColumnInfo(defaultValue = "LIGHT")
    val engineBrake: String = "LIGHT",
    /** When a voice announce plays: "DUCK" (-12 dB), "PAUSE" (engine silent during speech), "MIX" (no ducking). */
    @ColumnInfo(defaultValue = "DUCK")
    val engineDuckOnVoice: String = "DUCK",
    /** If true, engine only plays when wired/BT audio is routed to headphones (safety). */
    @ColumnInfo(defaultValue = "0")
    val engineHeadphonesOnly: Boolean = false,
    /** True once the one-time safety disclosure has been acknowledged. */
    @ColumnInfo(defaultValue = "0")
    val engineSafetyShown: Boolean = false
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
    RECORD_START(R.string.flic_action_record_start),
    RECORD_STOP(R.string.flic_action_record_stop),
    MEDIA_PLAY_PAUSE(R.string.flic_action_media_play),
    MEDIA_NEXT(R.string.flic_action_media_next),
    MEDIA_PREVIOUS(R.string.flic_action_media_prev)
}
