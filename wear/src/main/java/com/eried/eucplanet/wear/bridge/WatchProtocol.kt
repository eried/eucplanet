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
}

object WatchKeys {
    const val CONNECTED = "c"
    const val SPEED = "s"
    const val BATTERY = "b"
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
    /** Bumped on every snapshot so the watch always sees a fresh DataItem. */
    const val TIMESTAMP = "ts"
}

object WatchControl {
    const val HORN = "horn"
    const val LIGHT_ON = "light_on"
    const val LIGHT_OFF = "light_off"
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
    val voltage: Float = 0f,
    val current: Float = 0f,
    val pwmPercent: Float = 0f,
    val temperatureC: Float = 0f,
    val tripKm: Float = 0f,
    val torque: Float = 0f,
    val lightOn: Boolean = false,
    val maxSpeedKmh: Float = 0f,
    val hasHorn: Boolean = false,
    val hasLight: Boolean = false
)
