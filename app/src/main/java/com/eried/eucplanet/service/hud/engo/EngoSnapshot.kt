package com.eried.eucplanet.service.hud.engo

/**
 * The ride page as the glasses draw it, already formatted. Kept free of
 * Android and the repositories so [EngoLayout] is a pure function; the
 * [EngoPageBuilder] fills it from the live wheel, the history buffers and
 * navigation.
 *
 * The rider picks a layout ([EngoLayouts]) and what each of its numbered
 * elements shows; element 1 always has the biggest place.
 */
data class EngoPage(
    val connected: Boolean,
    val layout: EngoLayoutKind,
    /** Element 1 first; null leaves that element's place empty. */
    val elements: List<EngoReading?>,
    val font: EngoFontStyle = EngoFontStyle.PIXEL,
    /** The next turn while a route is guided, null otherwise. */
    val nav: EngoNav? = null,
    /** The rider's "show turns while navigating". */
    val turnsOn: Boolean = true,
)

/** One reading: a short title, its number, and how full its bar or graph is. */
data class EngoReading(
    /** Upper-case ASCII, unit included ("PWM %", "MAX SPD", "TOP"). */
    val title: String,
    /** Digits, sign and point only ("58", "-12.5"), or "--". */
    val value: String,
    /** How full the bar is, 0..1; null draws it empty. */
    val level: Float? = null,
    /** The graph's window, oldest first, each 0..1 of the graph's height. */
    val series: List<Float> = emptyList(),
    val band: EngoBand = EngoBand.NORMAL,
    /** How the rider wants it drawn; a place without room for it draws the number alone. */
    val style: EngoStyle = EngoStyle.NUMBER,
)

/** A reading's colour: plain, or the rider's PWM gauge bands. */
enum class EngoBand { NORMAL, OK, WARN, DANGER }

/** The next turn: which way, and how far, already formatted ("120 M"). */
data class EngoNav(val maneuver: EngoManeuver, val distance: String)

/** Turn types the nav corner can draw. */
enum class EngoManeuver { LEFT, SLIGHT_LEFT, RIGHT, SLIGHT_RIGHT, STRAIGHT, UTURN, ARRIVE }

/**
 * What the connected glasses can do. ENGO 3 is RG colour (81 colours); ENGO 2 is
 * 16 grey levels.
 */
data class EngoCaps(val colorRYG: Boolean)
