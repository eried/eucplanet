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
}
