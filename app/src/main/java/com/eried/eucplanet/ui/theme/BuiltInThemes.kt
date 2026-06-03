package com.eried.eucplanet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A named, read-only starter theme. The 3 built-ins cannot be edited or deleted;
 * editing one forks it into the working draft (see SettingsRepository / the theme
 * editor). Their token values reproduce the look the app shipped with the legacy
 * `themeMode` + default accent, so migrating an existing user is invisible.
 */
data class BuiltInTheme(val name: String, val colors: AppThemeColors)

object BuiltInThemes {

    // Shared values pulled from the legacy palette (Color.kt) so built-ins match
    // the previous schemes exactly. Default accent is Teal (the old "default").
    private val accent = AccentTeal
    private val navInk = Color(0xFF11151C)
    private val statusConnected = Color(0xFF2ECC40)

    private val darkColors = AppThemeColors(
            isLight = false,
            appBackground = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = DarkSurfaceVariant,
            dialog = DarkSurface,
            outline = Color(0xFF3A3A3A),
            scrim = Color(0xFF000000),
            textPrimary = TextPrimary,
            textSecondary = TextSecondary,
            textDisabled = Color(0xFF6E6E6E),
            primary = accent,
            onPrimary = DarkBackground,
            secondary = AccentGreen,
            tertiary = AccentOrange,
            selection = accent,
            statusGood = AccentGreen,
            statusWarn = AccentOrange,
            statusDanger = AccentRed,
            metricVoltage = AccentBlue,
            metricBattery = AccentGreen,
            metricTemp = AccentOrange,
            metricPosition = AccentPurple,
            metricAccel = AccentPink,
            gaugeTrack = DarkSurfaceVariant,
            gaugeFill = accent,
            gaugeWarn = AccentYellow,
            gaugeDanger = AccentRed,
            navRouteLine = accent,
            navPopupPanel = Color.White,
            navPopupInk = navInk,
            connectionActive = statusConnected,
            connectionIdle = TextSecondary,
    )

    val dark = BuiltInTheme("Dark", darkColors.fillDerived())

    val pureBlack = BuiltInTheme(
        "Pure Black",
        darkColors.copy(
            appBackground = BlackBackground,
            surface = BlackSurface,
            surfaceVariant = BlackSurfaceVariant,
            dialog = BlackSurface,
            gaugeTrack = BlackSurfaceVariant,
        ).fillDerived()
    )

    val light = BuiltInTheme(
        "Light",
        AppThemeColors(
            isLight = true,
            appBackground = LightBackground,
            surface = LightSurface,
            surfaceVariant = LightSurfaceVariant,
            dialog = LightSurface,
            outline = Color(0xFFD0D0D0),
            scrim = Color(0xFF000000),
            textPrimary = TextPrimaryLight,
            textSecondary = TextSecondaryLight,
            textDisabled = Color(0xFF9E9E9E),
            primary = accent,
            onPrimary = Color.White,
            secondary = AccentGreen,
            tertiary = AccentOrange,
            selection = accent,
            statusGood = AccentGreen,
            statusWarn = AccentOrange,
            statusDanger = AccentRed,
            metricVoltage = AccentBlue,
            metricBattery = AccentGreen,
            metricTemp = AccentOrange,
            metricPosition = AccentPurple,
            metricAccel = AccentPink,
            gaugeTrack = LightSurfaceVariant,
            gaugeFill = accent,
            gaugeWarn = AccentYellow,
            gaugeDanger = AccentRed,
            navRouteLine = accent,
            navPopupPanel = navInk,
            navPopupInk = Color.White,
            connectionActive = statusConnected,
            connectionIdle = TextSecondaryLight,
        ).fillDerived()
    )

    /** Declaration order = combo order: Light, Dark, Pure Black. */
    val all: List<BuiltInTheme> = listOf(light, dark, pureBlack)

    fun byName(name: String): BuiltInTheme? = all.firstOrNull { it.name == name }

    fun isBuiltIn(name: String): Boolean = byName(name) != null

    /** First-launch / migration pick: OS-light → Light, OS-dark → Pure Black. */
    fun forSystemDark(systemDark: Boolean): BuiltInTheme = if (systemDark) pureBlack else light
}
