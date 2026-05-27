package com.eried.eucplanet.garmin

/**
 * Wire vocabulary shared between phone and the Connect IQ companion in
 * `garmin-watch-app/`. Mirrors the Wear OS `WatchKeys` 1:1 on purpose, so a
 * future refactor can collapse both into a single `:protocol` module without
 * a wire-format break.
 *
 * Payload is a `Dictionary<String, Object>` per CIQ's `Communications.transmit`
 * contract. Keys stay short because every byte rides Bluetooth at 5 Hz.
 */
internal object GarminKeys {
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
    const val UNIT_SPEED = "us"
    const val UNIT_DISTANCE = "ud"
    const val UNIT_TEMP = "ut"
    const val ACCENT = "ac"
    const val TIMESTAMP = "ts"

    const val GPS_SPEED = "gs"
    const val GPS_SOURCE = "gsr"

    const val OPT_KEEP_ON = "wko"
    const val OPT_SHOW_WHEEL_BATT = "wsb"
    const val OPT_SHOW_PHONE_BATT = "wpb"
    const val OPT_SHOW_WATCH_BATT = "wwb"
    const val OPT_PWM_DISPLAY = "wpd"
    const val OPT_SHOW_SPEED_UNIT = "wsu"
    const val OPT_PRIORITIZE_PWM = "wpp"
    const val OPT_DIAL_ROTATION = "wrot"
    const val OPT_GAUGE_BAND = "wgb"
    const val OPT_GAUGE_ORANGE = "wgo"
    const val OPT_GAUGE_RED = "wgr"

    // Hardware-button bindings. The watch maps these onto whatever physical
    // buttons it has (Fenix: light/back/start/up/down; Edge: lap/start/back).
    // The watch app picks two slots and treats them analogously to the Wear OS
    // stem buttons.
    const val STEM1_CLICK = "s1c"
    const val STEM1_HOLD = "s1h"
    const val STEM2_CLICK = "s2c"
    const val STEM2_HOLD = "s2h"

    const val SCREEN1_CLICK = "b1c"
    const val SCREEN1_HOLD = "b1h"
    const val SCREEN2_CLICK = "b2c"
    const val SCREEN2_HOLD = "b2h"

    const val HAPTIC_ON_ACTION = "hap"

    const val NAV_ACTIVE = "na"
    const val NAV_ANGLE = "ng"
    const val NAV_PRIMARY = "np"
    const val NAV_DISTANCE = "nd"
    const val NAV_ARRIVED = "nar"

    /** Frame type discriminator. The companion sends snapshots; other types
     *  ("wake", "quit", "vibrate") are short messages with this key set. */
    const val KIND = "k"

    const val KIND_STATE = "state"
    const val KIND_WAKE = "wake"
    const val KIND_QUIT = "quit"
    /** Vibrate hint: companion sends `{k: "vibe", ms: <int>}`. */
    const val KIND_VIBRATE = "vibe"
    const val VIBRATE_MS = "ms"
}

/**
 * Control intents flowing watch → phone, mirroring `WatchControl` on the Wear
 * OS side. Sent as plain strings (single-key dictionary `{"cmd": <intent>}`)
 * so the phone listener can route without protobuf overhead.
 */
internal object GarminControl {
    const val HORN = "horn"
    const val LIGHT_ON = "light_on"
    const val LIGHT_OFF = "light_off"
    const val ACTION_PREFIX = "action:"
    /** Watch tells phone its build info on app launch, mirrors `/euc/watch_info`. */
    const val WATCH_INFO_PREFIX = "info:"
    /** Dictionary key for incoming control payloads from the watch. */
    const val PAYLOAD_KEY = "cmd"
}

/** UUID of the Connect IQ application in `garmin-watch-app/manifest.xml`.
 *  Must match the watch manifest verbatim or `getApplicationInfo` returns
 *  NOT_INSTALLED and the bridge never publishes. */
internal const val GARMIN_APP_UUID = "ee55b467-1529-4dfc-ac0f-c0d30c7cfdf5"

/** Connect IQ "store" application ID used when prompting the user to install
 *  the companion from the Connect IQ store. Filled in after the app is
 *  uploaded to the store; leave blank for sideload-only development. */
internal const val GARMIN_STORE_APP_ID = ""
