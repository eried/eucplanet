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

object WatchUnits {
    fun speed(kmh: Float, imperial: Boolean): Float = if (imperial) kmh * 0.621371f else kmh
    fun distance(km: Float, imperial: Boolean): Float = if (imperial) km * 0.621371f else km
    fun temperature(c: Float, imperial: Boolean): Float = if (imperial) c * 9f / 5f + 32f else c
    /**
     * Localized speed unit. Norwegian Bokmål uses "km/t", Dutch "km/u",
     * Russian "км/ч" etc., so we route through string resources rather than
     * hardcoding "km/h".
     */
    fun speedUnit(context: android.content.Context, imperial: Boolean): String =
        if (imperial) context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit_mph)
        else context.getString(com.eried.eucplanet.wear.R.string.watch_speed_unit)
    fun distanceUnit(imperial: Boolean): String = if (imperial) "mi" else "km"
    fun tempUnit(imperial: Boolean): String = if (imperial) "°F" else "°C"
}

internal val GaugeAccentBlue = AccentBlue
internal val GaugeAccentGreen = AccentGreen
internal val GaugeAccentYellow = AccentYellow
internal val GaugeAccentOrange = AccentOrange
internal val GaugeAccentRed = AccentRed
