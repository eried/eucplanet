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
    const val ACCENT = "ac"
    /** Bumped on every snapshot so the watch always sees a fresh DataItem. */
    const val TIMESTAMP = "ts"

    // --- Watch UI options pushed from the phone Settings -> Watch section ---
    const val OPT_KEEP_ON = "wko"
    const val OPT_SHOW_WHEEL_BATT = "wsb"
    const val OPT_SHOW_PHONE_BATT = "wpb"
    const val OPT_SHOW_WATCH_BATT = "wwb"
    /** "BAR" / "NUMBERS" / "BOTH". */
    const val OPT_PWM_DISPLAY = "wpd"
    const val OPT_SHOW_SPEED_UNIT = "wsu"
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
    // (greyed) horn + light buttons — V14 family always has these and the
    // watch never sees a wheel without them. Phone's WearBridge will overwrite
    // with the wheel's actual capabilities once telemetry starts.
    val hasHorn: Boolean = true,
    val hasLight: Boolean = true,
    val imperialUnits: Boolean = false,
    val accentKey: String = "default",
    // Watch UI options sourced from phone Settings.
    val keepScreenOn: Boolean = true,
    val showWheelBattery: Boolean = true,
    val showPhoneBattery: Boolean = true,
    val showWatchBattery: Boolean = true,
    val pwmDisplay: String = "BOTH",
    val showSpeedUnit: Boolean = true,
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
    val hapticOnAction: Boolean = false
)
