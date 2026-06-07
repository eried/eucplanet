package com.eried.eucplanet.ui.theme

/**
 * Packs the active theme's colors for the Wear / HUD / Garmin bridges so the
 * companions follow the rider's custom theme instead of the legacy accent key.
 * The colors come from the resolved active theme (ui/theme/ThemeController),
 * never a persisted blob.
 */
object ThemeAccent {
    /**
     * "#AARRGGBB" — the wire format the HUD already consumes and the Wear/Garmin
     * apps parse (hex when prefixed with '#', else a legacy palette key).
     */
    fun primaryArgb(colors: AppThemeColors): String = "#" + colors.primary.toHex()

    /**
     * Packs the active theme's watch-relevant colors into a single pipe-separated
     * string of "#"-less AARRGGBB values for the Data Layer. The watch parses it
     * with `parseWatchColors`; the FIELD ORDER here is the wire contract and must
     * stay in lockstep with the watch's `WatchColors`.
     */
    fun packForWatch(colors: AppThemeColors): String =
        listOf(
            colors.appBackground, colors.surface, colors.surfaceVariant, colors.gaugeTrack, colors.gaugeFill,
            colors.statusWarn, colors.gaugeDanger, colors.metricBattery, colors.statusDanger,
            colors.textPrimary, colors.textSecondary, colors.textDisabled, colors.onPrimary, colors.statusGood,
            colors.metricPosition, colors.metricVoltage, colors.primary
        ).joinToString("|") { it.toHex() }
}
