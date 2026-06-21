package com.eried.eucplanet.hudlink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS port of Android's `hud-protocol` wire format (`HudWire.kt`). The phone
 * dials a HUD device over `ws://<ip>:<port>/state`, streams [HudState] JSON at
 * 5 Hz, and decodes [HudCommand] frames the HUD sends back.
 *
 * Deliberately in package `hudlink` (NOT `hud.protocol`) so the Android `:app`,
 * which depends on BOTH `:shared` and `:hud-protocol`, never sees a duplicate
 * `com.eried.eucplanet.hud.protocol.HudState` class. The JSON encoding is
 * field-name based, so the package rename does not affect wire compatibility.
 *
 * Field names + defaults are kept IDENTICAL to the Android side — kotlinx
 * .serialization keys by property name, so any rename here would break the wire.
 * Units are canonical metric (km/h, °C, km); the HUD converts using the unit
 * codes. Floats may be NaN (no GPS fix) — the client's Json enables special
 * floating-point values.
 */
@Serializable
data class HudState(
    val protocolVersion: Int = PROTOCOL_MAJOR,
    val protocolMajor: Int = PROTOCOL_MAJOR,
    val protocolMinor: Int = PROTOCOL_MINOR,

    val connected: Boolean = false,
    val wheelName: String = "",

    // Live telemetry (canonical metric).
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val pwm: Float = 0f,
    val temperatureC: Float = 0f,
    val tripKm: Float = 0f,
    val totalKm: Float = 0f,
    val torque: Float = 0f,
    val lightOn: Boolean = false,

    val gaugeMaxKmh: Float = 30f,
    val gaugeOrangeThresholdPct: Int = 80,
    val gaugeRedThresholdPct: Int = 90,
    val showGaugeColorBand: Boolean = true,

    val unitSpeed: String = "kmh",
    val unitDistance: String = "km",
    val unitTemp: String = "C",

    val accentArgb: String = "#FF00C853",

    // GPS — iOS port has no GPS wired yet, so these stay at the no-fix defaults.
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsSpeedKmh: Float = Float.NaN,
    val gpsSource: String = "",
    val gpsHasFix: Boolean = false,
    val gpsHeadingDeg: Float = Float.NaN,
    val gpsAltitudeM: Float = Float.NaN,

    val wheelRollDeg: Float = 0f,
    val wheelPitchDeg: Float = 0f,

    val customOverlayJson: String = "",
    val enabledHudScreens: List<String> = emptyList(),

    val hudMapStyle: String = "",
    val hudMapContrastPct: Int = 100,
    val hudMapBrightnessPct: Int = 0,

    // Navigation popup mirror — unused on iOS (no nav engine yet).
    val navActive: Boolean = false,
    val navArrowAngleDeg: Float = 0f,
    val navPrimary: String = "",
    val navDistance: String = "",
    val navArrived: Boolean = false,

    val joystickUp: String = "",
    val joystickDown: String = "",
    val joystickLeft: String = "",
    val joystickRight: String = "",

    val timestampMs: Long = 0L,
) {
    companion object {
        const val PROTOCOL_MAJOR: Int = 1
        const val PROTOCOL_MINOR: Int = 7
    }
}

/**
 * One-shot commands the HUD sends back to the phone over the same socket.
 *
 * `@SerialName` is pinned to the Android side's default serial names (the
 * fully-qualified `com.eried.eucplanet.hud.protocol.*` class names) so the
 * polymorphic `"type"` discriminator the HUD emits decodes here despite this
 * port living in a different package.
 */
@Serializable
sealed class HudCommand {
    @Serializable
    @SerialName("com.eried.eucplanet.hud.protocol.HudCommand.Pair")
    data class Pair(
        val hudId: String,
        val hudVersion: String,
        val hudProtocolMajor: Int = 0,
        val hudProtocolMinor: Int = 0,
    ) : HudCommand()

    @Serializable
    @SerialName("com.eried.eucplanet.hud.protocol.HudCommand.ToggleLight")
    data object ToggleLight : HudCommand()

    @Serializable
    @SerialName("com.eried.eucplanet.hud.protocol.HudCommand.Horn")
    data object Horn : HudCommand()

    @Serializable
    @SerialName("com.eried.eucplanet.hud.protocol.HudCommand.StopNavigation")
    data object StopNavigation : HudCommand()

    @Serializable
    @SerialName("com.eried.eucplanet.hud.protocol.HudCommand.Action")
    data class Action(val slot: String) : HudCommand()
}

/** Service-discovery constants (mirrors Android's HudDiscovery). */
object HudDiscovery {
    const val DEFAULT_PORT: Int = 28080
    const val PATH_STATE: String = "/state"
    const val PATH_COMMAND: String = "/command"
    const val PATH_HEALTH: String = "/health"
}
