package com.eried.eucplanet.service.hud.engo

/** The page layouts the rider picks from. */
enum class EngoLayoutKind { COCKPIT, BIG, QUAD, TURN, MINIMAL }

/** How an element is drawn: its number alone, or with blocks, a smooth bar or a graph. */
enum class EngoStyle { NUMBER, BLOCKS, SMOOTH, GRAPH }

/**
 * Where each element of a layout sits, in rider-view pixels, (0,0) top-left
 * of the 304x256 panel, every box inclusive. Shared by the drawing
 * ([EngoLayout]) and the settings screen (the numbered badges on the preview,
 * and which styles each element can pick).
 *
 * Element numbers are by importance: element 1 is the biggest place in every
 * layout, so a rider switching layouts keeps the main reading main.
 */
object EngoLayouts {

    /** The most elements any layout shows. */
    const val MAX_ELEMENTS = 5

    private val ALL_STYLES = EngoStyle.entries.toList()
    private val NUMBER_ONLY = listOf(EngoStyle.NUMBER)

    /** One element's place. [group] places share one number size, so a row reads even. */
    class Spot(val element: Int, val geom: Geom, val styles: List<EngoStyle>, val group: Int = 0) {
        /** The rider-view box around the whole place, for the preview's badge. */
        val bounds: IntArray get() = geom.bounds
    }

    sealed class Geom {
        abstract val bounds: IntArray

        /** A big number with its title under it; a bar or graph between when the style has one. */
        class Hero(
            val x0: Int, val x1: Int,
            val numTop: Int, val numBottom: Int,
            /** The number's bottom when a bar or graph takes the band under it. */
            val numBottomWithVisual: Int,
            val visTop: Int, val visBottom: Int,
            val titleY: Int, val titleScale: Int,
        ) : Geom() {
            override val bounds get() = intArrayOf(x0, numTop, x1, titleY + EngoPixelFont.height(titleScale))
        }

        /** A title over a number, both right-aligned or both centred. */
        class Corner(
            val titleX0: Int, val x0: Int, val x1: Int,
            val titleY: Int, val valueY: Int, val valueMaxH: Int,
            val centred: Boolean,
        ) : Geom() {
            // A right-aligned corner is outlined around its number; its title may run further left.
            override val bounds get() = intArrayOf(if (centred) titleX0 else x0, titleY, x1, valueY + valueMaxH)
        }

        /** A column: the title at [y0], the bar or graph band, the number centred under it down to [y1]. */
        class Cell(val x0: Int, val y0: Int, val x1: Int, val y1: Int) : Geom() {
            override val bounds get() = intArrayOf(x0, y0, x1, y1)
        }
    }

    /** The next turn's place: an arrow drawn [thick] pixels wide and its distance, bright when it is the page's main news. */
    class Turn(
        val arrowBox: IntArray, val cx: Int, val cy: Int, val r: Int, val thick: Int,
        val distBox: IntArray, val distY: Int, val distScale: Int, val distCentred: Boolean,
        val distBright: Boolean = false,
    )

    class Spec(
        val kind: EngoLayoutKind,
        val spots: List<Spot>,
        val turn: Turn? = null,
        /** Dim lines (x0, y0, x1, y1) between places, drawn once. */
        val dividers: List<IntArray> = emptyList(),
    )

    // Two cells side by side along the bottom (Big, Turn).
    private fun bottomPair(first: Int) = listOf(
        Spot(first, Geom.Cell(16, 145, 143, 233), ALL_STYLES, group = 1),
        Spot(first + 1, Geom.Cell(160, 145, 287, 233), ALL_STYLES, group = 1),
    )

    private val PAIR_DIVIDER = intArrayOf(152, 146, 152, 229)

    private val COCKPIT = Spec(
        EngoLayoutKind.COCKPIT,
        spots = listOf(
            Spot(0, Geom.Hero(84, 220, 24, 94, 94, 0, 0, titleY = 98, titleScale = 2), NUMBER_ONLY),
            Spot(1, Geom.Corner(196, 224, 288, titleY = 8, valueY = 28, valueMaxH = 35, centred = false), NUMBER_ONLY),
        ) + (0 until 3).map { i ->
            val x0 = 14 + i * 95
            Spot(2 + i, Geom.Cell(x0, 145, x0 + 85, 226), ALL_STYLES, group = 1)
        },
        turn = Turn(intArrayOf(16, 14, 80, 60), 40, 36, 18, 3, intArrayOf(16, 62, 80, 92), 64, 2, distCentred = false),
        dividers = listOf(intArrayOf(104, 146, 104, 222), intArrayOf(199, 146, 199, 222)),
    )

    private val BIG = Spec(
        EngoLayoutKind.BIG,
        spots = listOf(
            Spot(0, Geom.Hero(16, 287, 14, 112, 88, 92, 112, titleY = 118, titleScale = 2), ALL_STYLES),
        ) + bottomPair(1),
        dividers = listOf(PAIR_DIVIDER),
    )

    private val QUAD = Spec(
        EngoLayoutKind.QUAD,
        spots = listOf(
            Spot(0, Geom.Cell(16, 14, 143, 116), ALL_STYLES, group = 1),
            Spot(1, Geom.Cell(160, 14, 287, 116), ALL_STYLES, group = 1),
            Spot(2, Geom.Cell(16, 138, 143, 240), ALL_STYLES, group = 1),
            Spot(3, Geom.Cell(160, 138, 287, 240), ALL_STYLES, group = 1),
        ),
        dividers = listOf(intArrayOf(152, 18, 152, 236), intArrayOf(20, 127, 284, 127)),
    )

    /** The Turn layout while a route is guided: the arrow big on the left, element 1 beside it. */
    private val TURN_ROUTE = Spec(
        EngoLayoutKind.TURN,
        spots = listOf(
            Spot(0, Geom.Corner(160, 160, 287, titleY = 22, valueY = 44, valueMaxH = 63, centred = true), NUMBER_ONLY),
        ) + bottomPair(1),
        turn = Turn(intArrayOf(16, 14, 143, 108), 80, 60, 40, 6, intArrayOf(16, 112, 143, 138), 115, 3,
            distCentred = true, distBright = true),
        dividers = listOf(PAIR_DIVIDER),
    )

    /** The Turn layout with no route: element 1 takes the arrow's place, as on Big. */
    private val TURN_IDLE = Spec(EngoLayoutKind.TURN, BIG.spots, dividers = BIG.dividers)

    private val MINIMAL = Spec(
        EngoLayoutKind.MINIMAL,
        spots = listOf(
            Spot(0, Geom.Hero(16, 287, 20, 196, 150, 158, 196, titleY = 210, titleScale = 3), ALL_STYLES),
        ),
    )

    private fun riding(kind: EngoLayoutKind): Spec = when (kind) {
        EngoLayoutKind.COCKPIT -> COCKPIT
        EngoLayoutKind.BIG -> BIG
        EngoLayoutKind.QUAD -> QUAD
        EngoLayoutKind.TURN -> TURN_IDLE
        EngoLayoutKind.MINIMAL -> MINIMAL
    }

    /**
     * The layout the glasses draw. Cockpit keeps its own turn corner; Turn
     * shows the turn whenever a route is guided, the switch or not; the others
     * give way to Turn during a route while the rider's switch is on.
     */
    fun resolve(kind: EngoLayoutKind, navigating: Boolean, turnsOn: Boolean): Spec = when {
        kind == EngoLayoutKind.COCKPIT -> COCKPIT
        kind == EngoLayoutKind.TURN -> if (navigating) TURN_ROUTE else TURN_IDLE
        navigating && turnsOn -> TURN_ROUTE
        else -> riding(kind)
    }

    /** Whether the next turn is drawn. */
    fun showsTurn(kind: EngoLayoutKind, navigating: Boolean, turnsOn: Boolean): Boolean =
        navigating && (kind == EngoLayoutKind.TURN || turnsOn)

    /** Whether "show turns while navigating" means anything here: Turn shows them anyway. */
    fun turnsSwitchApplies(kind: EngoLayoutKind): Boolean = kind != EngoLayoutKind.TURN

    /** No element picked to set up. */
    const val NONE = -1

    /**
     * The element picked on the preview, kept only while the page shown has a
     * place for it. Another layout, the route preview or the turns switch can
     * hide it; the pick then goes, so the editor never sets up an element the
     * rider cannot see.
     */
    fun keepSelection(picked: Int, kind: EngoLayoutKind, navigating: Boolean, turnsOn: Boolean): Int =
        if (resolve(kind, navigating, turnsOn).spots.any { it.element == picked }) picked else NONE

    /** Whether element [element] has no place in [kind] itself, only in the Turn layout during a route. */
    fun onlyDuringRoute(kind: EngoLayoutKind, element: Int): Boolean =
        riding(kind).spots.none { it.element == element }

    /** Element [element]'s place: in the riding layout, else in Turn when it only shows during a route. */
    fun spotOf(kind: EngoLayoutKind, element: Int): Spot? =
        riding(kind).spots.firstOrNull { it.element == element }
            ?: TURN_ROUTE.spots.firstOrNull { it.element == element }

    fun kindOf(name: String): EngoLayoutKind =
        EngoLayoutKind.entries.firstOrNull { it.name == name } ?: EngoLayoutKind.COCKPIT

    fun styleOf(name: String): EngoStyle =
        EngoStyle.entries.firstOrNull { it.name == name } ?: EngoStyle.NUMBER

    fun fontOf(name: String): EngoFontStyle =
        EngoFontStyle.entries.firstOrNull { it.name == name } ?: EngoFontStyle.PIXEL
}
