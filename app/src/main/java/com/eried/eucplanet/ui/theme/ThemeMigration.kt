package com.eried.eucplanet.ui.theme

/**
 * Resolved active theme: the tokens to render plus the bookkeeping the combo
 * needs (display name, whether it's an unsaved working draft).
 */
data class ResolvedTheme(
    val colors: AppThemeColors,
    val name: String,
    val dirty: Boolean,
)

/**
 * One-time bridge from the legacy `themeMode` + `accentColor` settings to the
 * custom theme system. Its job now is mainly to map the legacy `themeMode` to a
 * built-in theme NAME — that's all the sole caller (ThemeController.ensureResolved)
 * reads, rendering the clean built-in by name. It still folds the old accent into
 * the `primary`-derived tokens here, but the caller ignores those colors, so a
 * legacy custom-accent user does NOT look identical after upgrade.
 */
object ThemeMigration {

    fun migrate(themeMode: String, accentKey: String, systemDark: Boolean): ResolvedTheme {
        val builtIn = when (themeMode) {
            "light" -> BuiltInThemes.light
            "dark" -> BuiltInThemes.dark
            "black" -> BuiltInThemes.pureBlack
            // "system" (and the new default) follows the install rule once.
            else -> BuiltInThemes.forSystemDark(systemDark)
        }
        // The legacy accent only ever drove colorScheme.primary, which fed the
        // speed arc and the map route line — so apply it to those tokens too.
        val defaultAccent = isDefaultAccent(accentKey)
        val accent = accentColorFor(accentKey)
        val colors = if (defaultAccent) builtIn.colors else builtIn.colors.copy(
            primary = accent,
            selection = accent,
            navRouteLine = accent,
            gaugeFill = accent,
        )
        return ResolvedTheme(colors, builtIn.name, dirty = !defaultAccent)
    }
}
