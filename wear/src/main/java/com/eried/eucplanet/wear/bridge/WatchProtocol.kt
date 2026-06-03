package com.eried.eucplanet.wear.bridge

/**
 * Wire format shared between phone and watch.
 *
 * Wearable Data Layer paths:
 *  - `/euc/state`    phone -> watch, throttled telemetry snapshot (DataItem)
 *  - `/euc/control`  watch -> phone, button-press intents (Message)
 *
 * State snapshot packs into a DataMap; [WatchState] is the typed view. Control
 * intents are short strings on the message channel. Keeping the format tiny
 * because the Data Layer round-trips through Bluetooth on Watch Ultra and we
 * publish at 5 Hz when the wheel is connected.
 */
object WatchPaths {
    const val STATE = "/euc/state"
    const val CONTROL = "/euc/control"
    /** Phone-to-watch ping that wakes the watch app when the user opens the
     *  phone app. Watch listener launches MainActivity when it sees this. */
    const val WAKE = "/euc/wake"
    /** Phone-to-watch ping the phone sends when the user picks "Stop all" so
     *  the watch dismisses its dial instead of sitting on a stale frame. */
    const val QUIT = "/euc/quit"
    /** One-shot watch -> phone message sent on watch app launch carrying
     *  Build / BuildConfig info as a UTF-8 pipe-separated string so the
     *  Service Mode log on the phone can include both sides of the pair. */
    const val WATCH_INFO = "/euc/watch_info"
}

object WatchKeys {
    const val CONNECTED = "c"
    const val SPEED = "s"
    const val BATTERY = "b"
    const val PHONE_BATT = "b2"
    const val VOLTAGE = "v"
    const val CURRENT = "i"
    const val PWM = "p"
    const val TEMP = "t"
    const val TRIP_KM = "tr"
    const val TORQUE = "tq"
    const val LIGHT_ON = "l"
    const val MAX_SPEED = "ms"
    const val WHEEL_NAME = "n"
    const val HAS_HORN = "ch"
    const val HAS_LIGHT = "cl"
    const val IMPERIAL = "im"
    /** Resolved per-unit string codes from the phone's Units.effective*Unit.
     *  Speed "kmh"/"mph"/"ms"/"kn", distance "km"/"mi"/"m"/"ft"/"mil",
     *  temperature "C"/"F"/"K". Absent on phone builds older than the
     *  per-unit rework, fall back to [IMPERIAL] when missing. */
    const val UNIT_SPEED = "us"
    const val UNIT_DISTANCE = "ud"
    const val UNIT_TEMP = "ut"
    const val ACCENT = "ac"
    /** Packed custom-theme colors ("#"-less AARRGGBB, pipe-separated, fixed
     *  field order — see WatchColors / ThemeAccent.packForWatch). Lets the watch
     *  mirror the phone theme's background, gauge, battery and text colors.
     *  Absent on older phone builds → watch keeps its built-in palette. */
    const val THEME = "thm"
    /** Bumped on every snapshot so the watch always sees a fresh DataItem. */
    const val TIMESTAMP = "ts"

    /** GPS extra-speed readout, km/h. Float.NaN when there is nothing to
     *  show, mirrors the phone dashboard's gpsExtraSpeed indicator. */
    const val GPS_SPEED = "gs"
    /** GPS speed source: "EXTERNAL" (RaceBox box) / "PHONE" / "" (none). */
    const val GPS_SOURCE = "gsr"

    // --- Watch UI options pushed from the phone Settings -> Watch section ---
    const val OPT_KEEP_ON = "wko"
    const val OPT_SHOW_WHEEL_BATT = "wsb"
    const val OPT_SHOW_PHONE_BATT = "wpb"
    const val OPT_SHOW_WATCH_BATT = "wwb"
    /** "BAR" / "NUMBERS" / "BOTH". */
    const val OPT_PWM_DISPLAY = "wpd"
    const val OPT_SHOW_SPEED_UNIT = "wsu"
    /** When true, the dial enlarges PWM at the expense of the speed font. */
    const val OPT_PRIORITIZE_PWM = "wpp"
    /** Virtual rotation of the first watch screen, in degrees (-90..+90, step 5). */
    const val OPT_DIAL_ROTATION = "wrot"
    /** Phone's `showGaugeColorBand` toggle. When false, the gauge stays
     *  monochrome on the watch too. */
    const val OPT_GAUGE_BAND = "wgb"
    /** Phone's orange-zone threshold percentage of the speed range. */
    const val OPT_GAUGE_ORANGE = "wgo"
    /** Phone's red-zone threshold percentage. */
    const val OPT_GAUGE_RED = "wgr"

    // --- Hardware-button bindings (KEYCODE_STEM_1 / STEM_2). Stored as
    //     FlicAction.name strings; "NONE" disables the binding. ---
    const val STEM1_CLICK = "s1c"
    const val STEM1_HOLD = "s1h"
    const val STEM2_CLICK = "s2c"
    const val STEM2_HOLD = "s2h"

    // --- On-screen watch button bindings. Same FlicAction.name vocabulary;
    //     defaults are HORN / LIGHT_TOGGLE on the phone side. ---
    const val SCREEN1_CLICK = "b1c"
    const val SCREEN1_HOLD = "b1h"
    const val SCREEN2_CLICK = "b2c"
    const val SCREEN2_HOLD = "b2h"

    /** Global toggle: vibrate the watch briefly when an action fires. */
    const val HAPTIC_ON_ACTION = "hap"

    // --- Navigation mirror (phone popup → watch). NAV_ACTIVE already folds in
    //     the rider's opt-in toggle and the phone popup's minimized state. ---
    const val NAV_ACTIVE = "na"
    /** Arrow rotation in degrees, 0 = straight up. */
    const val NAV_ANGLE = "ng"
    /** Pre-formatted instruction line, e.g. "Turn left". */
    const val NAV_PRIMARY = "np"
    /** Pre-formatted distance line, e.g. "200 m". */
    const val NAV_DISTANCE = "nd"
    const val NAV_ARRIVED = "nar"
}

object WatchControl {
    const val HORN = "horn"
    const val LIGHT_ON = "light_on"
    const val LIGHT_OFF = "light_off"
    /**
     * Generic action passthrough: payload is a [FlicAction] name. Used by
     * the watch's stem-button handler when the bound action needs phone
     * routing (LOCK_TOGGLE, SAFETY_TOGGLE, RECORD_TOGGLE, VOICE_ANNOUNCE,
     * MEDIA_*). Horn / light keep their dedicated paths above for back-
     * compat with prior watch builds.
     */
    const val ACTION_PREFIX = "action:"
}

/**
 * Phone-to-watch hint paths. Distinct from `/euc/control` because the watch
 * is the one *receiving* these (control flows the other way), and we want a
 * separate listener path so the bridge service can tell which direction the
 * payload came from.
 */
object WatchHints {
    /**
     * Phone asks the watch to fire its own vibrator. Payload is a 4-byte
     * little-endian int = duration in milliseconds. Used by alarm rules with
     * `vibrateTarget = WATCH` or `BOTH`.
     */
    const val VIBRATE = "/euc/vibrate"
}

/**
 * Snapshot the watch UI renders from. Mirrors the bits of WheelData the watch
 * actually shows; expand cautiously since every field adds Data Layer payload.
 */
data class WatchState(
    val connected: Boolean = false,
    val wheelName: String = "",
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val phoneBatteryPercent: Int = 0,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val pwmPercent: Float = 0f,
    val temperatureC: Float = 0f,
    val tripKm: Float = 0f,
    val torque: Float = 0f,
    val lightOn: Boolean = false,
    val maxSpeedKmh: Float = 0f,
    // Default both to true so the disconnected dashboard still renders the
    // (greyed) horn + light buttons, V14 family always has these and the
    // watch never sees a wheel without them. Phone's WearBridge will overwrite
    // with the wheel's actual capabilities once telemetry starts.
    val hasHorn: Boolean = true,
    val hasLight: Boolean = true,
    /**
     * Resolved measurement-unit codes mirroring the phone's Units.kt:
     *  - [speedUnit]    "kmh" / "mph" / "ms" / "kn"
     *  - [distanceUnit] "km" / "mi" / "m" / "ft" / "mil"
     *  - [tempUnit]     "C" / "F" / "K"
     * Defaults are metric so a freshly-launched watch (pre-sync) renders
     * something sane; [WatchBridgeService] overwrites them from the phone,
     * deriving from the legacy K_IMPERIAL boolean for old phone builds.
     */
    val speedUnit: String = "kmh",
    val distanceUnit: String = "km",
    val tempUnit: String = "C",
    val accentKey: String = "default",
    /** Packed custom-theme colors from the phone (see [WatchKeys.THEME]); empty
     *  string means "not synced", so the watch falls back to its built-in palette. */
    val themePacked: String = "",
    // Watch UI options sourced from phone Settings.
    val keepScreenOn: Boolean = true,
    val showWheelBattery: Boolean = true,
    val showPhoneBattery: Boolean = true,
    val showWatchBattery: Boolean = true,
    val pwmDisplay: String = "BOTH",
    val showSpeedUnit: Boolean = true,
    val prioritizePwm: Boolean = false,
    val dialRotationDeg: Int = 0,
    /**
     * True once the watch has received its first telemetry DataMap from the phone
     * this session. Used to gate the unit label, before sync the watch has no
     * way to know which units the rider uses, so we hide it to avoid showing
     * the wrong one.
     */
    val phoneSynced: Boolean = false,
    val showGaugeBand: Boolean = false,
    val gaugeOrangeThresholdPct: Int = 65,
    val gaugeRedThresholdPct: Int = 85,
    val stem1Click: String = "NONE",
    val stem1Hold: String = "NONE",
    val stem2Click: String = "NONE",
    val stem2Hold: String = "NONE",
    val screen1Click: String = "HORN",
    val screen1Hold: String = "NONE",
    val screen2Click: String = "LIGHT_TOGGLE",
    val screen2Hold: String = "NONE",
    val hapticOnAction: Boolean = false,
    /**
     * GPS extra speed in km/h, or [Float.NaN] when there is nothing to show.
     * Mirrors the phone dashboard's gpsExtraSpeed indicator. NaN is the
     * default so an older phone build that never sends the key leaves the
     * watch readout hidden rather than showing a bogus 0.
     */
    val gpsSpeedKmh: Float = Float.NaN,
    /** "EXTERNAL" (RaceBox box), "PHONE", or "" when no GPS speed to show. */
    val gpsSource: String = "",
    // --- Navigation popup mirror ---
    /** True when the phone popup is active, not minimized, and the rider
     *  enabled "Show navigation" for the watch. */
    val navActive: Boolean = false,
    /** Arrow rotation, degrees (0 = up). */
    val navAngle: Float = 0f,
    val navPrimary: String = "",
    val navDistance: String = "",
    val navArrived: Boolean = false
)
