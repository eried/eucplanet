package com.eried.eucplanet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The active theme's primary (accent) color, used by the Wear / HUD / Garmin
 * bridges so the companions follow the rider's custom theme instead of the
 * legacy accent key. Falls back to the legacy accent palette when the theme
 * snapshot hasn't been seeded yet (pre-migration).
 */
object ThemeAccent {
    fun primaryColor(activeThemeColorsJson: String, accentColorKey: String): Color {
        if (activeThemeColorsJson.isNotEmpty()) {
            ThemeJson.colorsFromString(activeThemeColorsJson, BuiltInThemes.pureBlack.colors)
                ?.let { return it.primary }
        }
        return accentColorFor(accentColorKey)
    }

    /**
     * "#AARRGGBB" — the wire format the HUD already consumes and the Wear/Garmin
     * apps parse (hex when prefixed with '#', else a legacy palette key).
     */
    fun primaryArgb(activeThemeColorsJson: String, accentColorKey: String): String =
        "#" + primaryColor(activeThemeColorsJson, accentColorKey).toHex()

    /**
     * Packs the active theme's watch-relevant colors into a single pipe-separated
     * string of "#"-less AARRGGBB values for the Data Layer. The watch parses it
     * with `parseWatchColors`; the FIELD ORDER here is the wire contract and must
     * stay in lockstep with the watch's `WatchColors`. Returns "" before the
     * theme snapshot is seeded, which tells the watch to keep its built-in palette.
     */
    fun packForWatch(activeThemeColorsJson: String): String {
        if (activeThemeColorsJson.isEmpty()) return ""
        val c = ThemeJson.colorsFromString(activeThemeColorsJson, BuiltInThemes.pureBlack.colors)
            ?: return ""
        return listOf(
            c.appBackground, c.surface, c.surfaceVariant, c.gaugeTrack, c.gaugeFill,
            c.statusWarn, c.gaugeDanger, c.metricBattery, c.statusDanger,
            c.textPrimary, c.textSecondary, c.textDisabled, c.onPrimary, c.statusGood,
            c.metricPosition, c.metricVoltage, c.primary
        ).joinToString("|") { it.toHex() }
    }
}
