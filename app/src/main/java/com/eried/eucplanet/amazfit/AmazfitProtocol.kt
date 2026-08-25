package com.eried.eucplanet.amazfit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire vocabulary shared between the phone and the Zepp OS companion in
 * `amazfit-watch-app/`. Mirrors `GarminKeys` (and through it the Wear OS
 * `WatchKeys`) 1:1 on purpose, so the three watch surfaces read the same
 * snapshot and a future refactor can collapse them into one `:protocol`
 * module without a wire-format break.
 *
 * Transport differs from both: the watch cannot be pushed to. Its Side
 * Service (JavaScript running inside the Zepp phone app) polls
 * `GET http://127.0.0.1:AMAZFIT_PORT/state` and the phone answers with a
 * JSON object carrying these keys. One-shot phone-to-watch messages ride in
 * the [EVENTS] list of the next response.
 */
internal object AmazfitKeys {
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
    /** Mirrors AppSettings.watchCloseOnExit: the watch self-closes after 20 s
     *  of silence when true, so a phone killed before its QUIT left still
     *  takes the dial down with it. */
    const val OPT_CLOSE_ON_EXIT = "wce"

    const val STEM1_CLICK = "s1c"
    const val STEM1_HOLD = "s1h"
    const val STEM2_CLICK = "s2c"
    const val STEM2_HOLD = "s2h"
    /** Third hardware button (Down on Garmin and Amazfit), click only. */
    const val STEM3_CLICK = "s3c"

    const val SCREEN1_CLICK = "b1c"
    const val SCREEN1_HOLD = "b1h"
    const val SCREEN2_CLICK = "b2c"
    const val SCREEN2_HOLD = "b2h"

    const val HAPTIC_ON_ACTION = "hap"

    /** True while the phone's Service Mode is recording. The watch reports
     *  its input events (`debug:` controls) only while this is set, so riders
     *  who never open Service Mode get no extra watch-to-phone traffic. */
    const val DIAG = "dg"

    const val NAV_ACTIVE = "na"
    const val NAV_ANGLE = "ng"
    const val NAV_PRIMARY = "np"
    const val NAV_DISTANCE = "nd"
    const val NAV_ARRIVED = "nar"

    /** Frame type discriminator, always [KIND_STATE] on the top level. Inside
     *  an [EVENTS] entry it names the one-shot: [KIND_QUIT] or [KIND_VIBRATE]. */
    const val KIND = "k"
    const val KIND_STATE = "state"
    const val KIND_QUIT = "quit"
    const val KIND_VIBRATE = "vibe"
    /** Vibrate duration inside a [KIND_VIBRATE] event. Lives one level down,
     *  so unlike Garmin it never collides with [MAX_SPEED]. */
    const val VIBRATE_MS = "ms"

    /** Milliseconds the watch waits after a response before it polls again.
     *  Follows the rider's watchUpdateRate tier (see [amazfitPollIntervalMsFor]). */
    const val POLL_MS = "pi"
    /** One-shot events queued since the previous poll, drained on read. */
    const val EVENTS = "ev"
}

/**
 * Control intents flowing watch to phone as `POST /control` with a JSON body
 * `{"cmd": <intent>}`. Same vocabulary as `GarminControl` minus the heartbeat:
 * on this transport the poll itself is the heartbeat.
 */
internal object AmazfitControl {
    const val HORN = "horn"
    const val LIGHT_ON = "light_on"
    const val LIGHT_OFF = "light_off"
    const val ACTION_PREFIX = "action:"
    /** Watch tells the phone its build info once on launch:
     *  `info:model=<name>|fw=<version>|api=<level>`. */
    const val WATCH_INFO_PREFIX = "info:"
    /** Input-event report from the watch (key codes, which binding fired).
     *  Only sent while [AmazfitKeys.DIAG] is true in the frames. */
    const val DEBUG_PREFIX = "debug:"
    const val PAYLOAD_KEY = "cmd"
}

/** Loopback port the phone listens on. The watch app carries the same number
 *  in `amazfit-watch-app/utils/protocol.js`; change both or neither. */
internal const val AMAZFIT_PORT = 28193
internal const val AMAZFIT_PATH_STATE = "/state"
internal const val AMAZFIT_PATH_CONTROL = "/control"

/**
 * Interval between frames the phone hands the Side Service, per
 * `AppSettings.watchUpdateRate` tier. Each frame is one ~1 KB Bluetooth push
 * to the watch, and the simulator sustains about 7 a second, so FAST at 4 Hz
 * keeps a comfortable margin. Unlike Garmin (capped near 1 Hz by Connect IQ)
 * this link is not rate-limited; the tiers match Wear OS more closely now.
 */
internal fun amazfitPollIntervalMsFor(rate: String): Int = when (rate) {
    "CONSERVATIVE" -> 1000
    "FAST" -> 250
    else -> 500
}

/** Tiny JSON glue: the snapshot is built as a plain `Map<String, Any>` (the
 *  shape `GarminBridge.encodeSnapshot` uses) and serialised here. */
internal object AmazfitJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(map: Map<String, Any?>): String = toElement(map).toString()

    /** Returns the `cmd` string of a `{"cmd": ...}` body, or null when the
     *  body is not that. Never throws: the watch is an untrusted peer. */
    fun cmdOf(body: String): String? = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val cmd = obj[AmazfitControl.PAYLOAD_KEY] ?: return null
        (cmd as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (_: Exception) {
        null
    }

    private fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(if (value.isFinite()) value else -1f)
        is Double -> JsonPrimitive(if (value.isFinite()) value else -1.0)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
        is Iterable<*> -> JsonArray(value.map { toElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
