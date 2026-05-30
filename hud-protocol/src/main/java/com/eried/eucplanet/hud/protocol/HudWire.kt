package com.eried.eucplanet.hud.protocol

import kotlinx.serialization.Serializable

/**
 * Wire format between the phone app (`:app`) and the HUD companion (`:hud`).
 *
 * Lives in its own module so both Gradle projects compile against the same
 * Kotlin types and the JSON encoding can't drift between sides. The HUD is a
 * passive renderer: every field here is something the HUD knows how to draw
 * on one of its four screens. Anything the HUD cannot use stays on the phone.
 *
 * Versioning: [protocolVersion] is bumped only when a field is removed or its
 * semantics change. Additions are always backwards-compatible — the HUD-side
 * `Json { ignoreUnknownKeys = true }` decoder silently drops fields it does
 * not know about, so a newer phone can still feed an older HUD APK.
 *
 * Units: all telemetry numbers are kept in the canonical metric form the phone
 * already uses internally (speed km/h, temperature °C, distance km). The HUD
 * does the imperial/etc. conversion locally using [unitSpeed]/[unitDistance]/
 * [unitTemp] so we don't have to teach the wire encoder about user prefs.
 */
@Serializable
data class HudState(
    val protocolVersion: Int = PROTOCOL_VERSION,

    /** True when the phone has a live BLE link to the wheel. */
    val connected: Boolean = false,
    /** BLE-advertised wheel name, empty when no wheel paired. */
    val wheelName: String = "",

    // --- Live telemetry (canonical metric) ---
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val pwm: Float = 0f,
    val temperatureC: Float = 0f,
    val tripKm: Float = 0f,
    val torque: Float = 0f,
    val lightOn: Boolean = false,

    /** Gauge max in km/h, mirrors the phone dashboard's gauge ceiling. */
    val gaugeMaxKmh: Float = 30f,
    /** PWM % at which the gauge goes orange (warning band). */
    val gaugeOrangeThresholdPct: Int = 80,
    /** PWM % at which the gauge goes red (critical band). */
    val gaugeRedThresholdPct: Int = 90,
    /** Whether the colour band should be drawn. */
    val showGaugeColorBand: Boolean = true,

    /** Resolved per-unit codes from the phone's settings. See `Units.kt`. */
    val unitSpeed: String = "kmh",
    val unitDistance: String = "km",
    val unitTemp: String = "C",

    /** Accent colour as an `#AARRGGBB` hex string from phone settings. */
    val accentArgb: String = "#FF00C853",

    // --- GPS (phone-side or external receiver) ---
    /** Latitude in WGS84 degrees, 0.0 when there is no fix. */
    val latitude: Double = 0.0,
    /** Longitude in WGS84 degrees, 0.0 when there is no fix. */
    val longitude: Double = 0.0,
    /** GPS speed in km/h when available, else NaN. */
    val gpsSpeedKmh: Float = Float.NaN,
    /** Source of [gpsSpeedKmh]: "PHONE" / "EXTERNAL" / "" (none). */
    val gpsSource: String = "",
    /** True when the phone has any fresh GPS fix at all (any source). */
    val gpsHasFix: Boolean = false,
    /** Current heading in degrees, 0 = north, +clockwise. NaN when the GPS
     *  hasn't reported a bearing yet. The HUD uses this to rotate the map
     *  so the rider's direction of travel always points up. */
    val gpsHeadingDeg: Float = Float.NaN,

    // --- Navigation popup mirror ---
    /** Whether to render the turn-by-turn overlay/screen. */
    val navActive: Boolean = false,
    /** Arrow angle in degrees, 0=straight up, +clockwise. */
    val navArrowAngleDeg: Float = 0f,
    /** Primary instruction line, e.g. "Turn left onto Storgata". */
    val navPrimary: String = "",
    /** Distance line, e.g. "120 m". */
    val navDistance: String = "",
    /** True when the rider is at the final destination. */
    val navArrived: Boolean = false,

    /**
     * Server clock at the moment of capture in epoch millis. Lets the HUD
     * compute a "frame freshness" signal independent of its own wall clock,
     * and lets the dedup-by-content snapshot pipeline force a re-emit by
     * just bumping the timestamp when no other field changed.
     */
    val timestampMs: Long = 0L
) {
    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}

/**
 * One-shot commands the HUD sends back to the phone. Posted to /command as
 * `{"type": "...", ...}`. Each remote-button binding on the HUD maps to one.
 */
@Serializable
sealed class HudCommand {
    /** Sent on initial pairing. Phone uses [hudId] to remember "this HUD" in
     *  diagnostics; it does NOT gate anything (the HUD has read-only state). */
    @Serializable
    data class Pair(val hudId: String, val hudVersion: String) : HudCommand()

    /** Toggle the wheel's headlight if it has one. */
    @Serializable
    data object ToggleLight : HudCommand()

    /** Sound the wheel's horn if it has one. */
    @Serializable
    data object Horn : HudCommand()

    /** Stop the current navigation route. */
    @Serializable
    data object StopNavigation : HudCommand()
}

/**
 * Service-discovery constants. Kept here so the phone advertises and the HUD
 * resolves the same name without typos.
 */
object HudDiscovery {
    /** Bonjour/Zeroconf service type. The trailing `.` is added by JmDNS. */
    const val SERVICE_TYPE: String = "_eucplanet._tcp.local."
    /** Default port; overridable per phone if 28080 collides on the LAN. */
    const val DEFAULT_PORT: Int = 28080
    /** TXT-record key for the wire protocol version. HUD refuses to pair
     *  against a phone advertising a higher major version than it speaks. */
    const val TXT_VERSION: String = "v"
    /** TXT-record key for the SSE state-stream path. */
    const val TXT_STATE_PATH: String = "state"
    /** TXT-record key for the command POST path. */
    const val TXT_COMMAND_PATH: String = "cmd"
    /** TXT-record key for the map-tile proxy path prefix. */
    const val TXT_TILES_PATH: String = "tiles"

    /** Canonical endpoint paths. */
    const val PATH_STATE: String = "/state"
    const val PATH_COMMAND: String = "/command"
    const val PATH_TILES_PREFIX: String = "/tiles"
    const val PATH_HEALTH: String = "/health"
}
