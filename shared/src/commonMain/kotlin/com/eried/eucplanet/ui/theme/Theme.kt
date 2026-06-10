package com.eried.eucplanet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 72.sp,
        letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    )
)

/**
 * Drives the whole app from one [AppThemeColors] (the active theme). The Material
 * [androidx.compose.material3.ColorScheme] is derived from the tokens so existing
 * `MaterialTheme.colorScheme.*` reads keep working, and the raw tokens are
 * exposed via [LocalAppColors] for the colors that bypass the scheme.
 *
 * `colors` is a stable immutable object while the editor is closed, so providing
 * it costs nothing in recomposition.
 */
@Composable
fun EucPlanetTheme(
    colors: AppThemeColors = BuiltInThemes.pureBlack.colors,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = AppTypography,
            content = content
        )
    }
}
