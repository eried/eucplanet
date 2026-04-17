package com.eried.evendarkerbot.ui.theme

import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF0D0D0D)
val DarkSurface = Color(0xFF1A1A1A)
val DarkSurfaceVariant = Color(0xFF2A2A2A)

val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEEEEE)

val AccentBlue = Color(0xFF4FC3F7)
val AccentGreen = Color(0xFF66BB6A)
val AccentDarkGreen = Color(0xFF2E7D32)
val AccentYellow = Color(0xFFFFCA28)
val AccentRed = Color(0xFFEF5350)
val AccentOrange = Color(0xFFFFA726)
val AccentPurple = Color(0xFFAB47BC)
val AccentTeal = Color(0xFF26C6DA)
val AccentPink = Color(0xFFEC407A)

val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFF9E9E9E)
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextSecondaryLight = Color(0xFF616161)

data class AccentOption(val key: String, val label: String, val color: Color)

const val AccentKeyDefault = "default"

val AccentOptions = listOf(
    AccentOption(AccentKeyDefault, "Default", AccentTeal),
    AccentOption("green", "Green", AccentGreen),
    AccentOption("dark_green", "Dark green", AccentDarkGreen),
    AccentOption("orange", "Orange", AccentOrange),
    AccentOption("pink", "Pink", AccentPink),
    AccentOption("purple", "Purple", AccentPurple),
    AccentOption("teal", "Teal", AccentTeal),
    AccentOption("red", "Red", AccentRed)
)

fun accentColorFor(key: String): Color =
    AccentOptions.firstOrNull { it.key == key }?.color ?: AccentBlue

fun isDefaultAccent(key: String): Boolean = key == AccentKeyDefault
