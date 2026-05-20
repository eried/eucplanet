package com.eried.eucplanet.wear.ui

import androidx.compose.ui.graphics.Color

/**
 * Tiny mirror of the phone's accent palette and unit helpers. We deliberately
 * duplicate instead of pulling the phone module in: the wear APK ships
 * separately and stays small, and the values here change on the order of once
 * a release. Keys are stable strings sent over the Data Layer in
 * [com.eried.eucplanet.wear.bridge.WatchKeys.ACCENT].
 */
private val AccentBlue = Color(0xFF4FC3F7)
private val AccentGreen = Color(0xFF66BB6A)
private val AccentDarkGreen = Color(0xFF2E7D32)
private val AccentYellow = Color(0xFFFFCA28)
private val AccentRed = Color(0xFFEF5350)
private val AccentOrange = Color(0xFFFFA726)
private val AccentPurple = Color(0xFFAB47BC)
private val AccentTeal = Color(0xFF26C6DA)
private val AccentPink = Color(0xFFEC407A)

fun accentColorFor(key: String): Color = when (key) {
    "default", "teal" -> AccentTeal
    "green" -> AccentGreen
    "dark_green" -> AccentDarkGreen
    "orange" -> AccentOrange
    "pink" -> AccentPink
    "purple" -> AccentPurple
    "red" -> AccentRed
    "blue" -> AccentBlue
    "yellow" -> AccentYellow
    else -> AccentTeal
}

/**
 * Watch-side mirror of the phone's `com.eried.eucplanet.util.Units`. The wheel
 * always sends km/h, km and °C over the Data Layer; these convert into the
 * rider's chosen units. [unit] strings are the same codes the phone resolves:
 *  - speed:    "kmh" / "mph" / "ms" / "kn"
 *  - distance: "km" / "mi" / "m" / "ft" / "mil"
 *  - temperature: "C" / "F" / "K"
 * Keep the conversion factors in lockstep with the phone's Units.kt.
 */
object WatchUnits {
    fun speed(kmh: Float, unit: String): Float = when (unit) {
        "mph" -> kmh * 0.621371f
        "ms" -> kmh / 3.6f
        "kn" -> kmh / 1.852f
        else -> kmh
    }

    fun distance(km: Float, unit: String): Float = when (unit) {
        "mi" -> km * 0.621371f
        "m" -> km * 1000f
        "ft" -> km * 3280.84f
        "mil" -> km / 10f
        else -> km
    }

    fun temperature(c: Float, unit: String): Float = when (unit) {
        "F" -> c * 9f / 5f + 32f
        "K" -> c + 273.15f
        else -> c
    }

    /**
     * Localized speed unit. Norwegian Bokmål uses "km/t", Dutch "km/u",
     * Russian "км/ч" etc., so km/h and mph route through string resources;
     * m/s and knots are technical abbreviations that stay constant.
     */
    fun speedUnit(context: android.content.Context, unit: String): String = when (unit) {
        "mph" -> context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit_mph)
        "ms" -> context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit_ms)
        "kn" -> context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit_kn)
        else -> context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit)
    }

    fun distanceUnit(unit: String): String = when (unit) {
        "mi" -> "mi"
        "m" -> "m"
        "ft" -> "ft"
        "mil" -> "mil"
        else -> "km"
    }

    fun tempUnit(unit: String): String = when (unit) {
        "F" -> "°F"
        "K" -> "K"
        else -> "°C"
    }
}

internal val GaugeAccentBlue = AccentBlue
internal val GaugeAccentGreen = AccentGreen
internal val GaugeAccentYellow = AccentYellow
internal val GaugeAccentOrange = AccentOrange
internal val GaugeAccentRed = AccentRed
internal val GaugeAccentPurple = AccentPurple

/**
 * Colour for the GPS extra-speed readout, matched to the phone dashboard:
 * an external GPS box (RaceBox) shows purple, the phone's own GPS shows blue.
 */
internal fun gpsSourceColor(source: String): Color = when (source) {
    "EXTERNAL" -> AccentPurple
    "PHONE" -> AccentBlue
    else -> AccentPurple
}
