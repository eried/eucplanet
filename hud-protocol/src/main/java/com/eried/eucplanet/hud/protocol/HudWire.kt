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
 * Versioning: see [PROTOCOL_MAJOR] / [PROTOCOL_MINOR] below. Additions are
 * always backwards-compatible because the JSON decoder runs with
 * `ignoreUnknownKeys = true`; the major version only bumps when a field is
 * REMOVED, RENAMED, or its semantics change (units, sentinel values).
 *
 * The legacy [protocolVersion] is kept as an alias for the major version so
 * old HUD APKs that only know that field still get a sensible value.
 *
 * Units: all telemetry numbers are kept in the canonical metric form the phone
 * already uses internally (speed km/h, temperature °C, distance km). The HUD
 * does the imperial/etc. conversion locally using [unitSpeed]/[unitDistance]/
 * [unitTemp] so we don't have to teach the wire encoder about user prefs.
 */
@Serializable
data class HudState(
    /** Legacy single-int version, equal to [PROTOCOL_MAJOR]. Kept on the
     *  wire so HUD APKs built before MAJOR/MINOR was split still see a
     *  recognisable version field. New code should read
     *  [protocolMajor] / [protocolMinor] instead. */
    val protocolVersion: Int = PROTOCOL_MAJOR,
    /** Breaking-change version. The HUD rejects frames whose major
     *  exceeds the one it speaks; the phone shows an "update HUD" hint
     *  when the HUD pair-back reports a lower major. */
    val protocolMajor: Int = PROTOCOL_MAJOR,
    /** Additive-change version. New fields bump this; old HUDs ignore
     *  the fields silently (they still read the frame). Phone shows a
     *  soft "update available" hint when HUD reports a lower minor. */
    val protocolMinor: Int = PROTOCOL_MINOR,

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
    /** GPS altitude in metres. NaN when the fix doesn't include altitude
     *  (some devices report 0 instead -- we still pass it through). The
     *  HUD keeps its own short rolling buffer to draw a sparkline; the
     *  phone only sends the current value. */
    val gpsAltitudeM: Float = Float.NaN,

    /** Wheel roll (lean) in degrees, +right. From wheel BLE telemetry
     *  (InMotion / Begode / KingSong all report it). 0 when the wheel
     *  isn't reporting it -- the HUD treats 0-degenerate as "no data" by
     *  not drawing the horizon. */
    val wheelRollDeg: Float = 0f,
    /** Wheel pitch (lean forward/back) in degrees, +forward. Same caveat
     *  as roll re availability. */
    val wheelPitchDeg: Float = 0f,

    /** JSON of the Overlay Studio preset the rider picked to render as
     *  the HUD's "Custom" screen. Empty = no custom overlay. The HUD
     *  parses this lazily and only draws element types it supports;
     *  unknown types are silently dropped. */
    val customOverlayJson: String = "",

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
        /**
         * Breaking-change version. Bump WHEN:
         *  - a field is removed or renamed
         *  - a field changes units (km/h -> m/s) or sentinels (0 -> NaN)
         *  - a HudCommand variant changes meaning
         * Old HUDs receiving a higher [protocolMajor] than they speak MUST
         * stop rendering live data and tell the rider to update.
         */
        const val PROTOCOL_MAJOR: Int = 1

        /**
         * Additive-change version. Bump WHEN:
         *  - a new field is added to [HudState] or to a [HudCommand] variant
         *  - a new [HudCommand] variant is added
         *  - a new field is added to the embedded [customOverlayJson] payload
         * Old HUDs ignore the new field but the link keeps working. Phone
         * surfaces a soft "update available" hint when the HUD's reported
         * minor is below ours.
         */
        const val PROTOCOL_MINOR: Int = 0

        /** Legacy alias. New code should read [PROTOCOL_MAJOR] / [PROTOCOL_MINOR]. */
        @Deprecated(
            "Split into PROTOCOL_MAJOR / PROTOCOL_MINOR",
            ReplaceWith("PROTOCOL_MAJOR")
        )
        const val PROTOCOL_VERSION: Int = PROTOCOL_MAJOR
    }
}

/**
 * Outcome of comparing the protocol version on the OTHER side of the link
 * against ours. Both the phone and the HUD compute this independently from
 * the version fields each side carries in its messages.
 */
enum class VersionCompat {
    /** Both sides on the same major + minor. No UI surface needed. */
    EXACT,

    /** Same major, our minor is HIGHER than the remote: we ship features
     *  the remote doesn't render, but the wire decoder is fine. Surface
     *  a soft "update available" hint. */
    REMOTE_BEHIND_MINOR,

    /** Same major, our minor is LOWER than the remote: the remote ships
     *  features we don't render. Frames still decode (extra fields are
     *  ignored). Soft hint pointing at OUR update channel. */
    REMOTE_AHEAD_MINOR,

    /** Remote major is LOWER than ours. Wire frames probably still parse
     *  but key fields may be missing or semantically wrong. Surface a
     *  blocking banner: rider must update the REMOTE side. */
    REMOTE_BEHIND_MAJOR,

    /** Remote major is HIGHER than ours. We may not understand fields
     *  the remote is sending; safer to stop showing live data. Surface
     *  a blocking banner: rider must update OUR side. */
    REMOTE_AHEAD_MAJOR;

    /** True for the two cases where the rider should be hard-blocked
     *  from trusting the readout. */
    val isBlocking: Boolean
        get() = this == REMOTE_BEHIND_MAJOR || this == REMOTE_AHEAD_MAJOR

    /** True when the rider should see a hint but the link still works. */
    val isHint: Boolean
        get() = this == REMOTE_BEHIND_MINOR || this == REMOTE_AHEAD_MINOR

    companion object {
        /** Compare a [remote] (major, minor) against the local
         *  [PROTOCOL_MAJOR] / [PROTOCOL_MINOR] from this side's POV. */
        fun classify(remoteMajor: Int, remoteMinor: Int): VersionCompat = when {
            remoteMajor < HudState.PROTOCOL_MAJOR -> REMOTE_BEHIND_MAJOR
            remoteMajor > HudState.PROTOCOL_MAJOR -> REMOTE_AHEAD_MAJOR
            remoteMinor < HudState.PROTOCOL_MINOR -> REMOTE_BEHIND_MINOR
            remoteMinor > HudState.PROTOCOL_MINOR -> REMOTE_AHEAD_MINOR
            else -> EXACT
        }
    }
}

/**
 * One-shot commands the HUD sends back to the phone. Posted to /command as
 * `{"type": "...", ...}`. Each remote-button binding on the HUD maps to one.
 */
@Serializable
sealed class HudCommand {
    /** Sent on initial pairing. Phone uses [hudId] to remember "this HUD" in
     *  diagnostics; it does NOT gate anything (the HUD has read-only state).
     *
     *  [hudProtocolMajor] / [hudProtocolMinor] are the HUD's own protocol
     *  version. The phone classifies them via [VersionCompat.classify] and
     *  decides whether to show an "update HUD" hint, a blocking banner, or
     *  nothing. Defaults of 0 / 0 cover older HUD APKs that pair without
     *  these fields -- the phone treats absence as "major 1, minor 0"
     *  (pre-split baseline). */
    @Serializable
    data class Pair(
        val hudId: String,
        val hudVersion: String,
        val hudProtocolMajor: Int = 0,
        val hudProtocolMinor: Int = 0
    ) : HudCommand()

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
