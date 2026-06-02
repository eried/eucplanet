package com.eried.eucplanet.wear.ui

import androidx.compose.runtime.staticCompositionLocalOf
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

fun accentColorFor(key: String): Color {
    // The phone now sends the active theme's primary as "#AARRGGBB"; parse it.
    // Legacy palette keys still resolve (older phones / the adb test intent).
    if (key.startsWith("#")) parseHexColor(key)?.let { return it }
    return when (key) {
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
}

private fun parseHexColor(s: String): Color? {
    val clean = s.removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> Color((0xFF000000L or clean.toLong(16)).toInt())
            8 -> Color(clean.toLong(16).toInt())
            else -> null
        }
    }.getOrNull()
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

// Gauge band defaults; the live values now come from the phone theme via
// WatchColors. Kept as the pre-sync fallback palette.
internal val GaugeAccentGreen = AccentGreen
internal val GaugeAccentOrange = AccentOrange
internal val GaugeAccentRed = AccentRed

/**
 * The watch's view of the rider's custom phone theme. The phone packs these
 * colors into a single pipe-separated `#`-less AARRGGBB string (see
 * `ThemeAccent.packForWatch`) sent over the Data Layer under
 * [com.eried.eucplanet.wear.bridge.WatchKeys.THEME]; the watch parses it back
 * with [parseWatchColors] and exposes it via [LocalWatchColors]. Defaults
 * reproduce the watch's previous fixed palette so an un-synced watch (or an
 * older phone that doesn't send the string) looks exactly as before.
 *
 * Field order is the wire contract — keep it in lockstep with the phone's
 * `ThemeAccent.packForWatch`.
 */
data class WatchColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val gaugeTrack: Color,
    val gaugeFill: Color,
    val gaugeWarn: Color,
    val gaugeDanger: Color,
    val battery: Color,
    val batteryLow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val onAccent: Color,
    val safe: Color,
    val gpsExternal: Color,
    val gpsPhone: Color,
    val accent: Color,
) {
    companion object {
        val Default = WatchColors(
            background = Color.Black,
            surface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF2A2A2A),
            gaugeTrack = Color(0xFF2A2A2A),
            gaugeFill = GaugeAccentGreen,
            gaugeWarn = GaugeAccentOrange,
            gaugeDanger = GaugeAccentRed,
            battery = GaugeAccentGreen,
            batteryLow = Color(0xFFE53935),
            textPrimary = Color.White,
            textSecondary = Color(0xFF9AA0A6),
            textDisabled = Color(0xFF606060),
            onAccent = Color.Black,
            safe = GaugeAccentGreen,
            gpsExternal = AccentPurple,
            gpsPhone = AccentBlue,
            accent = AccentTeal,
        )
    }
}

/** Parse the phone's packed theme string; any missing/bad field keeps the default. */
fun parseWatchColors(packed: String): WatchColors {
    if (packed.isBlank()) return WatchColors.Default
    val p = packed.split("|")
    val d = WatchColors.Default
    fun c(i: Int, fallback: Color): Color =
        p.getOrNull(i)?.let { parseHexColor(it) } ?: fallback
    return WatchColors(
        background = c(0, d.background),
        surface = c(1, d.surface),
        surfaceVariant = c(2, d.surfaceVariant),
        gaugeTrack = c(3, d.gaugeTrack),
        gaugeFill = c(4, d.gaugeFill),
        gaugeWarn = c(5, d.gaugeWarn),
        gaugeDanger = c(6, d.gaugeDanger),
        battery = c(7, d.battery),
        batteryLow = c(8, d.batteryLow),
        textPrimary = c(9, d.textPrimary),
        textSecondary = c(10, d.textSecondary),
        textDisabled = c(11, d.textDisabled),
        onAccent = c(12, d.onAccent),
        safe = c(13, d.safe),
        gpsExternal = c(14, d.gpsExternal),
        gpsPhone = c(15, d.gpsPhone),
        accent = c(16, d.accent),
    )
}

/** Active watch theme colors, provided at the [WatchApp] root. */
val LocalWatchColors = staticCompositionLocalOf { WatchColors.Default }
