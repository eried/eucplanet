package com.eried.eucplanet.ui.navigator

/**
 * A colour string as `android.graphics.Color.parseColor` reads it, as an ARGB
 * int: `#RRGGBB`, `#AARRGGBB`, or one of the names it knows. Throws
 * IllegalArgumentException on anything else, as parseColor does. The map's
 * page colours and the share palette are data shared with the web viewer,
 * which is why they arrive as text rather than theme tokens.
 */
fun parseColorString(color: String): Int {
    if (color.startsWith("#")) {
        val hex = color.substring(1)
        val value = hex.takeIf { it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            ?.toLongOrNull(16)
        return when {
            value == null -> throw IllegalArgumentException("Unknown color")
            hex.length == 6 -> (value or 0xFF000000L).toInt()
            hex.length == 8 -> value.toInt()
            else -> throw IllegalArgumentException("Unknown color")
        }
    }
    return NAMED[color.lowercase()]?.toInt() ?: throw IllegalArgumentException("Unknown color")
}

private val NAMED = mapOf(
    "black" to 0xFF000000L, "darkgray" to 0xFF444444L, "gray" to 0xFF888888L,
    "lightgray" to 0xFFCCCCCCL, "white" to 0xFFFFFFFFL, "red" to 0xFFFF0000L,
    "green" to 0xFF00FF00L, "blue" to 0xFF0000FFL, "yellow" to 0xFFFFFF00L,
    "cyan" to 0xFF00FFFFL, "magenta" to 0xFFFF00FFL, "aqua" to 0xFF00FFFFL,
    "fuchsia" to 0xFFFF00FFL, "darkgrey" to 0xFF444444L, "grey" to 0xFF888888L,
    "lightgrey" to 0xFFCCCCCCL, "lime" to 0xFF00FF00L, "maroon" to 0xFF800000L,
    "navy" to 0xFF000080L, "olive" to 0xFF808000L, "purple" to 0xFF800080L,
    "silver" to 0xFFC0C0C0L, "teal" to 0xFF008080L,
)
