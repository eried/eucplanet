package com.eried.eucplanet.data.model

import com.eried.eucplanet.R

/**
 * The full set of rider preferences. Lives in DataStore as one JSON blob
 * ([com.eried.eucplanet.data.store.SettingsStore]) so adding a new field is
 * just a one-line data-class change — no DB migration, no risk of losing
 * rider state on upgrade. The `id` field is a no-op legacy artifact kept so
 * older code that copy()'d with `id = 1` still compiles.
 */
data class AppSettings(
    val id: Int = 1,

    // Connection
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val autoConnect: Boolean = true,

    // What happens when the user swipes back from the main dashboard. Values:
    //   "ASK"        — show the exit dialog (legacy behavior, default)
    //   "BACKGROUND" — silently send the activity to background, service keeps running
    //   "STOP_ALL"   — stop the service and finish the activity
    // Storage keys are language-independent so locale switches don't break the setting.
    val backButtonAction: String = "ASK",

    // Speed settings (sent to wheel) - the "normal" mode values
    val tiltbackSpeedKmh: Float = 50f,
    val alarmSpeedKmh: Float = 40f,

    // Legal-mode speed (applied when legal toggle is ON)
    val safetyTiltbackKmh: Float = 20f,
    val safetyAlarmKmh: Float = 18f,

    /**
     * Per-wheel speed calibration as a percentage offset (-20..+20). Applied
     * at the source where adapters publish telemetry so alarms, voice,
     * dashboard and recording all see the calibrated speed. Stored here for
     * the current session and mirrored to the connected wheel's
     * [com.eried.eucplanet.data.model.WheelProfile] so reconnecting restores
     * the rider's chosen calibration.
     */
    val speedCalibrationOffsetPct: Float = 0f,

    // Voice
    val voiceEnabled: Boolean = true,
    // Independent toggle for the periodic (every N seconds) status announcements. When false,
    // voice still works for triggered events (manual button, Flic, alarms) but the periodic
    // loop is silent. Toggled from the dashboard via long-press on the Voice action.
    val voicePeriodicEnabled: Boolean = true,
    val voiceOnlyWhenConnected: Boolean = true,
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
    val autoRecordStartInMotion: Boolean = true,
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
    val flic3Address: String? = null,
    val flic3Name: String = "Button 3",
    val flic3Click: String = "NONE",
    val flic3DoubleClick: String = "NONE",
    val flic3Hold: String = "NONE",

    // Flic button 4
    val flic4Address: String? = null,
    val flic4Name: String = "Button 4",
    val flic4Click: String = "NONE",
    val flic4DoubleClick: String = "NONE",
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
    val autoVolumeCurve: String = "0:1.0,25:1.0,50:1.5,75:2.0",
    val autoVolumeBaselinePercent: Int = -1,

    // Display units
    // imperialUnits is legacy: kept only as the migration fallback for the three
    // per-unit fields below. Never read directly outside the Units.kt resolvers.
    val imperialUnits: Boolean = false,
    val unitSpeed: String = "",     // "" | "kmh" | "mph" | "ms"   ("" = not migrated)
    val unitDistance: String = "",  // "" | "km"  | "mi"  | "m"
    val unitTemp: String = "",      // "" | "C"   | "F"   | "K"

    val phoneKeepScreenOn: Boolean = false,

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
    val showGaugeColorBand: Boolean = false,
    // Percentages of the full speed sweep where orange and red zones begin (yellow fills below orange).
    val gaugeOrangeThresholdPct: Int = 65,
    val gaugeRedThresholdPct: Int = 85,
    // Haptic feedback on dashboard action button taps.
    val hapticFeedback: Boolean = true,
    // "AMPS" or "WATTS" — long-press the amps card to switch.
    val currentDisplayMode: String = "AMPS",

    // Backup folder (SAF tree URI on local storage; companion sync app handles cloud upload)
    val syncFolderUri: String? = null,
    val lastSettingsBackupAt: Long? = null,
    /** Snapshot name of the most-recent backup, null for the unnamed default. */
    val lastSettingsBackupName: String? = null,

    // External BLE GPS pairing (RaceBox today; future Draggy/VBox/etc. share this slot).
    // Three values stored: BLE MAC, advertised name (for display), and the source-family
    // enum name as a string ("RACEBOX") so we know which adapter to instantiate on connect.
    val externalGpsAddress: String? = null,
    val externalGpsName: String? = null,
    val externalGpsSource: String? = null,

    // RaceBox accelerometer axis remap. The device can be mounted in any
    // orientation; the rider's notion of "left/right", "forward/back" and
    // "up/down" may not align with the physical X / Y / Z the box reports.
    // Each entry says which raw axis (signed) becomes the corresponding
    // output axis. Allowed values: "X", "-X", "Y", "-Y", "Z", "-Z".
    // Identity (X→X, Y→Y, Z→Z) is the default and covers a wheel-pedal mount
    // with the box's logo facing up.
    val raceboxMapX: String = "X",
    val raceboxMapY: String = "Y",
    val raceboxMapZ: String = "Z",
    /**
     * Master switch for additional GPS data. When OFF (default), the app
     * doesn't capture or log extra GPS samples, and the whole Additional GPS
     * sub-section in Settings collapses — no external connect attempts, no
     * dashboard dot. When ON, samples flow from the phone or an external box
     * per [gpsPrioritizeExternal].
     */
    val gpsLogAdditional: Boolean = false,
    /**
     * When ON and an external GPS box is connected, its samples are the
     * dashboard's "extra speed" source. When OFF (or when no external box is
     * available) the phone's own GPS speed is used instead.
     */
    val gpsPrioritizeExternal: Boolean = true,
    /** Show the extra-GPS speed indicator on the dashboard speed dial. */
    val gpsShowOnDashboard: Boolean = true,

    // --- Wear OS companion (only takes effect when a Wear OS watch is paired) ---
    val watchKeepScreenOn: Boolean = true,
    val watchAutoStart: Boolean = true,
    /**
     * When the user picks "Stop all" from the phone exit dialog, also close
     * the watch companion app so its dial doesn't sit on a stale frame after
     * the phone tears the session down. On by default since the watch app
     * has no value without the phone feeding it telemetry.
     */
    val watchCloseOnExit: Boolean = true,
    val watchShowWheelBattery: Boolean = true,
    val watchShowPhoneBattery: Boolean = true,
    val watchShowWatchBattery: Boolean = true,
    /** "BAR", "NUMBERS", or "BOTH". */
    val watchPwmDisplay: String = "BOTH",
    val watchShowSpeedUnit: Boolean = true,
    val watchEnableGpsSpeed: Boolean = false,
    /**
     * When true the watch dial inverts the size hierarchy on its first screen:
     * the PWM bar + number become the focal element, the speed reading shrinks.
     * Useful when the rider cares more about cutout headroom than current speed.
     */
    val watchPrioritizePwm: Boolean = false,
    /**
     * Virtual rotation applied to the watch's first screen only, in degrees
     * (–90..+90, step 5). Lets the rider tilt the dial so it reads naturally with
     * their wrist orientation when the wheel is in motion. Doesn't affect the
     * other watch screens or any phone UI.
     */
    val watchDialRotationDeg: Int = 0,

    /**
     * Hardware-button bindings on the watch (Galaxy Watch Ultra exposes the
     * orange Action button as STEM_1 and the bottom side button as STEM_2;
     * Pixel Watch only has one). Stored as the [FlicAction] enum name so the
     * picker can reuse the same UI/string set as Flic and Volume keys. The
     * Wear OS side reads these via the Data Layer publish, intercepts
     * KEYCODE_STEM_* in MainActivity, and either fires a local control
     * intent or routes to the phone over /euc/control.
     */
    val watchStem1Click: String = "NONE",
    val watchStem1Hold: String = "NONE",
    val watchStem2Click: String = "NONE",
    val watchStem2Hold: String = "NONE",

    /**
     * On-screen watch button bindings. Two configurable buttons; tap fires the
     * "click" action, long-press fires the "hold" action. Same FlicAction
     * vocabulary as Flic / Volume / Stem buttons. Defaults match the wheel's
     * most-used controls (Horn, Light) so out-of-the-box behavior matches
     * the previous hardcoded buttons.
     */
    val watchScreen1Click: String = "HORN",
    val watchScreen1Hold: String = "NONE",
    val watchScreen2Click: String = "LIGHT_TOGGLE",
    val watchScreen2Hold: String = "NONE",

    /**
     * If true, the watch vibrates briefly whenever a button-bound action
     * fires (tap or hold) so the user gets tactile confirmation.
     */
    val watchHapticOnAction: Boolean = true,

    /**
     * Live-data update rate for the dashboard and watch. Drives the realtime
     * poll-and-push loop interval: "CONSERVATIVE" (500 ms, easiest on phone /
     * watch battery), "NORMAL" (250 ms, the default) or "FAST" (150 ms, most
     * responsive). Stored as a stable key so the millisecond mapping can be
     * retuned later without a settings migration.
     */
    val watchUpdateRate: String = "NORMAL",

    // --- Motor Sound generator ---
    //
    // Synthesises a virtual engine driven by live (speed, pwm) telemetry. Goes
    // through the media stream so it mixes with music; the user controls how it
    // behaves under voice announces via [engineDuckOnVoice].
    val engineSoundEnabled: Boolean = false,
    /** Preset key. See [com.eried.eucplanet.audio.EngineProfile.PROFILES]. */
    val engineType: String = "FOUR_STROKE_SINGLE",
    /** In-app gain 0..1 over the media stream. */
    val engineVolume: Float = 0.6f,
    /**
     * Legacy. Was a paired "fixed volume" toggle (with [engineVolume] as the slider) that
     * could disable the speed curve. The current UI always uses the curve so this field
     * is unused — kept only for backup/sync compatibility with v0.5.x exports.
     */
    val engineVolumeAutoEnabled: Boolean = false,
    /**
     * Encoded 4-point curve at 0/25/50/75 km/h, values in 0..1. The curve IS the engine
     * volume — there's no separate fixed-volume slider any more. Format matches
     * [com.eried.eucplanet.service.parseVolumeCurve]: "speed:mult,..."
     * Default: full volume parked for pedestrian awareness, drop to 10% by cruise speed,
     * silent at top.
     */
    val engineVolumeAutoCurve: String = "0:1.00,25:0.10,50:0.10,75:0.00",
    /** "OPEN", "HALF", "MUFFLED" — controls high-harmonic rolloff. */
    val engineMuffler: String = "HALF",
    /** "OFF", "FOUR", "SIX". Ignored for engines whose profile is gearless (synth/futuristic). */
    val engineGearbox: String = "FOUR",
    /** "ALWAYS" (always idling when connected), "FADE" (fade after parked), "MOVING" (only when moving). */
    val engineIdleBehavior: String = "FADE",
    /** "SMOOTH" (no pops), "STANDARD", "BACKFIRE" (heavy pops on decel). */
    val engineDecelChar: String = "STANDARD",
    /** "OFF", "LIGHT", "STRONG" — engine-brake whine layered during sustained decel/regen. */
    val engineBrake: String = "LIGHT",
    /** When a voice announce plays: "DUCK" (-12 dB), "PAUSE" (engine silent during speech), "MIX" (no ducking). */
    val engineDuckOnVoice: String = "DUCK",
    /** If true, engine only plays when wired/BT audio is routed to headphones (safety). */
    val engineHeadphonesOnly: Boolean = false
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
