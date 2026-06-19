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
    /** Lifetime odometer in km. Wired in protocol minor 6 -- prior HUD
     *  builds default this to 0f and show the ODO custom-overlay element
     *  as 0.0, which is what older HUDs already did anyway. */
    val totalKm: Float = 0f,
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

    /** Ordered list of HUD screens the rider has enabled, by stable id
     *  ("Dashboard", "Camera", "Telemetry", "Custom", "CustomCam",
     *  "Map", "Nav"). Empty list = default (all seven in declaration
     *  order). Non-empty = use EXACTLY this list in this order; any
     *  screen not in the list is hidden from the carousel.
     *
     *  Lets the rider trim and reorder screens on the phone side
     *  without having to power-cycle the HUD. HUD-side controller
     *  applies the new list on every accepted frame; if the rider is
     *  currently viewing a screen that just got removed, the HUD
     *  snaps to the first enabled screen so they're never stuck on
     *  a "no such screen" state. */
    val enabledHudScreens: List<String> = emptyList(),

    /** Map-tile style code the HUD should use for its Map screen and
     *  for any MAP element inside a Custom overlay. Empty = HUD picks
     *  its compiled-in default. Recognised codes mirror CartoCDN's
     *  basemap raster styles: "voyager", "dark_matter",
     *  "dark_matter_nolabels", "light_all", "positron". Any other value
     *  is treated as empty so a rider can't get a blank map by typing
     *  garbage into a future free-form picker. */
    val hudMapStyle: String = "",
    /** Per-axis tile post-processing applied as a ColorMatrix on the
     *  drawn tile bitmaps. Both default to neutral; the HUD skips the
     *  filter entirely when both are at the neutral values.
     *  contrast: 50..200 percent, 100 = neutral.
     *  brightness: -100..100, 0 = neutral.
     *  Older HUDs (PROTOCOL_MINOR < 5) ignore these fields silently. */
    val hudMapContrastPct: Int = 100,
    val hudMapBrightnessPct: Int = 0,

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

    // --- Joystick long-press action labels ---
    /** Human-readable label of the action bound to the HUD joystick
     *  LONG-PRESS in each direction, resolved from the phone's settings via
     *  the ActionCatalog. Empty = None/unset for that direction. The HUD
     *  briefly shows a "+"-shaped joystick guide on screen change so the
     *  rider can recall what each long-press does. Config lives on the phone,
     *  so the labels have to ride along in the state frame. Older HUDs
     *  (PROTOCOL_MINOR < 7) ignore these fields and never draw the guide. */
    val joystickUp: String = "",
    val joystickDown: String = "",
    val joystickLeft: String = "",
    val joystickRight: String = "",

    // --- Rear-view radar (Garmin Varia) ---
    /** True when a rear-view radar is paired AND has a live BLE link.
     *  Lets the HUD distinguish "no radar configured" (idle) from
     *  "radar connected, lane clear" (empty [radarTargets]). Older HUDs
     *  (PROTOCOL_MINOR < 8) ignore this and never draw a radar widget. */
    val radarConnected: Boolean = false,
    /** Radar device battery percentage, or -1 when the radar does not
     *  publish it / no radar is paired. */
    val radarBatteryPercent: Int = -1,
    /** Vehicles the radar currently tracks, nearest-first is NOT guaranteed
     *  -- the renderer sorts. Empty = the radar reports a clear lane (only
     *  meaningful when [radarConnected]). Capped phone-side at 8 to bound
     *  the frame size; the Varia rarely reports more. */
    val radarTargets: List<RadarTargetWire> = emptyList(),

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
         *
         * 8: added rear-view radar fields ([radarConnected],
         *    [radarBatteryPercent], [radarTargets]) and the RADAR overlay
         *    element type. Older HUDs ignore them and skip the radar widget.
         */
        const val PROTOCOL_MINOR: Int = 8

        /** Legacy alias. New code should read [PROTOCOL_MAJOR] / [PROTOCOL_MINOR]. */
        @Deprecated(
            "Split into PROTOCOL_MAJOR / PROTOCOL_MINOR",
            ReplaceWith("PROTOCOL_MAJOR")
        )
        const val PROTOCOL_VERSION: Int = PROTOCOL_MAJOR
    }
}

/**
 * One vehicle tracked by the rear-view radar, in the compact form sent over
 * the wire. Mirrors the phone-side `RadarThreat` minus the fields the HUD
 * doesn't need (vendor track bookkeeping, first-seen timestamp).
 *
 * The Varia is rear-facing and reports range + closing speed only -- there
 * is NO bearing, so the HUD cannot place a car left/right; the "mirror" view
 * mode lights both sides together by [level].
 */
@Serializable
data class RadarTargetWire(
    /** Stable per-car track id while the vehicle is in view. */
    val id: Int = 0,
    /** Range from the rider's rear, in metres (Varia saturates ~140 m). */
    val distanceM: Int = 0,
    /** Closing speed in km/h; positive = approaching. */
    val approachSpeedKmh: Int = 0,
    /** Severity, as the `ThreatLevel` ordinal: 0 = NONE, 1 = APPROACHING,
     *  2 = FAST_APPROACH. An int (not the phone enum) so the protocol module
     *  stays free of app types and tolerates future levels. */
    val level: Int = 0
)

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

    /** Fire a configurable phone-side action bound to a HUD joystick long-press.
     *  [slot] is the direction: "UP" / "DOWN" / "LEFT" / "RIGHT". The phone maps
     *  the slot to a configured ActionCatalog key (config lives on the phone,
     *  like Flic / Wear) and dispatches it. Older phones that predate this
     *  command will reject the frame; the HUD only sends it once paired, so a
     *  protocol-version mismatch surfaces as the usual "update phone" hint. */
    @Serializable
    data class Action(val slot: String) : HudCommand()
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
