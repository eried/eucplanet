package com.eried.eucplanet.service.hud.engo

/**
 * The ride page as the glasses draw it, already formatted. Kept free of
 * Android and the repositories so [EngoLayout] is a pure function; the
 * [EngoPageBuilder] fills it from the live wheel, the history buffers and
 * navigation.
 *
 * Top left the next turn, centre the speed, top right one reading the rider
 * picked, and a row of three readings along the bottom, each a title, a bar or
 * a graph, and a number centred under them.
 */
data class EngoPage(
    val connected: Boolean,
    /** The speed in the rider's unit, digits only ("42"), or "--". */
    val speed: String,
    /** The speed unit under it ("KM/H"). */
    val speedUnit: String,
    /** The reading in the top right corner; null leaves it empty. */
    val top: EngoReading?,
    /** The three readings along the bottom, left to right; null leaves one empty. */
    val bottom: List<EngoReading?>,
    /** Graphs of the last minute instead of bars. */
    val graphs: Boolean,
    /** The next turn, while a route is guided and turns are on. */
    val nav: EngoNav? = null,
)

/** One reading: a short title, its number, and how full its bar or graph is. */
data class EngoReading(
    /** Upper-case ASCII, unit included ("PWM %", "MAX SPD", "TOP"). */
    val title: String,
    /** Digits, sign and point only ("58", "-12.5"), or "--". */
    val value: String,
    /** How full the bar is, 0..1; null draws it empty. */
    val level: Float? = null,
    /** The last minute, oldest first, each 0..1 of the graph's height. */
    val series: List<Float> = emptyList(),
    val band: EngoBand = EngoBand.NORMAL,
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
