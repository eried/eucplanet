package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ENGO ride page as drawn, for every layout, style and font: batching, the
 * delta that redraws only what changed, and, through [EngoRaster], where the
 * pixels actually land: inside each widget's own box, never on a neighbour,
 * numbers centred in their places.
 */
class EngoLayoutTest {

    private val engo2 = EngoCaps(colorRYG = false)
    private val engo3 = EngoCaps(colorRYG = true)
    private val W = EngoLayout.W
    private val H = EngoLayout.H

    private fun reading(
        title: String, value: String, style: EngoStyle = EngoStyle.BLOCKS, level: Float = 0.5f,
        band: EngoBand = EngoBand.NORMAL,
    ) = EngoReading(title, value, level, List(64) { (it % 10) / 10f }, band, style)

    private fun page(
        layout: EngoLayoutKind = EngoLayoutKind.COCKPIT,
        elements: List<EngoReading?> = listOf(
            reading("KM/H", "42", EngoStyle.NUMBER), reading("TOP", "87", EngoStyle.NUMBER),
            reading("BAT %", "76"), reading("PWM %", "58", level = 0.58f, band = EngoBand.OK), reading("MOT°C", "58"),
        ),
        font: EngoFontStyle = EngoFontStyle.PIXEL,
        nav: EngoNav? = null,
        turnsOn: Boolean = true,
    ) = EngoPage(true, layout, elements, font, nav, turnsOn)

    private fun all(style: EngoStyle, vararg v: String) =
        listOf("MAX PWM", "PK MOT W", "RNG KM", "PHONE %", "AVG SPD").mapIndexed { i, t -> reading(t, v[i % v.size], style) }

    /** Every layout, each style everywhere, wide numbers, the turn on and off, every font. */
    private val worst: List<EngoPage> = buildList {
        for (layout in EngoLayoutKind.entries) for (style in EngoStyle.entries) for (font in EngoFontStyle.entries) {
            add(page(layout, all(style, "188", "-2350", "123.4", "100", "--"), font))
            add(page(layout, all(style, "8", "42"), font, nav = EngoNav(EngoManeuver.UTURN, "1.2 KM")))
        }
        for (layout in EngoLayoutKind.entries) {
            add(page(layout, List(5) { null }, nav = EngoNav(EngoManeuver.ARRIVE, "")))
            add(page(layout, nav = EngoNav(EngoManeuver.LEFT, "120 M"), turnsOn = false))
        }
    }

    private fun ops(cmds: List<ByteArray>) = cmds.map { it[1].toInt() and 0xFF }

    private fun lit(px: IntArray) = px.indices.filter { px[it] != EngoRaster.BLACK }

    @Test
    fun theWholePageIsOneBatchedFrame() {
        val cmds = EngoLayout.render(page(), engo3)
        assertArrayEquals(ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD), cmds.first())
        assertArrayEquals(ActiveLookProtocol.clear(), cmds[1])
        assertArrayEquals(ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH), cmds.last())
    }

    @Test
    fun noTextCommands_thePageDrawsItsOwnFonts() {
        // The glasses' fonts are not the size their doc says; every string is rectangles.
        for (p in worst) for (caps in listOf(engo2, engo3)) {
            val o = ops(EngoLayout.render(p, caps))
            assertFalse(0x37 in o)
            assertFalse(0x3E in o)
        }
    }

    @Test
    fun everyWidgetDrawsOnlyInsideItsBoxes_andBoxesNeverOverlap() {
        for (p in worst) {
            val ws = EngoLayout.widgets(p, engo3)
            val decor = BooleanArray(W * H)
            for (w in ws) {
                val px = lit(EngoRaster.render(w.cmds, color = true))
                if (w.boxes.isEmpty()) { px.forEach { decor[it] = true }; continue }
                for (i in px) {
                    val x = i % W; val y = i / W
                    assertTrue("${p.layout} ${w.id} pixel ($x,$y) outside its box",
                        w.boxes.any { x in it[0]..it[2] && y in it[1]..it[3] })
                }
            }
            val boxes = ws.flatMap { w -> w.boxes.map { w.id to it } }
            for (a in boxes.indices) for (b in a + 1 until boxes.size) {
                val (ia, ra) = boxes[a]; val (ib, rb) = boxes[b]
                val apart = ra[2] < rb[0] || rb[2] < ra[0] || ra[3] < rb[1] || rb[3] < ra[1]
                assertTrue("${p.layout}: $ia and $ib boxes overlap", apart)
            }
            // Erasing a box must never wipe the corner marks or the dividers.
            for ((id, r) in boxes) for (y in r[1]..r[3]) for (x in r[0]..r[2]) {
                assertFalse("${p.layout}: $id box covers decoration at ($x,$y)", decor[y * W + x])
            }
        }
    }

    @Test
    fun everythingStaysOnThePanel() {
        for (p in worst) for ((id, r) in EngoLayout.widgets(p, engo3).flatMap { w -> w.boxes.map { w.id to it } }) {
            assertTrue("${p.layout} $id", r[0] >= 0 && r[1] >= 0 && r[2] < W && r[3] < H)
        }
    }

    @Test
    fun cellNumbersAreCentred_andARowSharesOneSize() {
        for (font in EngoFontStyle.entries) {
            val ws = EngoLayout.widgets(page(font = font), engo3)
            val heights = (3..5).map { n ->
                val w = ws.first { it.id == "e${n}v" }
                val px = lit(EngoRaster.render(w.cmds, color = true))
                val xs = px.map { it % W }
                val box = w.boxes.single()
                val left = xs.min() - box[0]
                val right = box[2] - xs.max()
                assertTrue("$font e$n off centre: $left vs $right", kotlin.math.abs(left - right) <= 1)
                px.map { it / W }.let { it.max() - it.min() }
            }
            assertEquals("$font row of one size", 1, heights.toSet().size)
        }
    }

    @Test
    fun aNumberOnlyCellKeepsTheRowsLine_unlessTheWholeRowIsNumbers() {
        fun valueTop(p: EngoPage, id: String) = EngoLayout.widgets(p, engo3).first { it.id == id }
            .let { w -> lit(EngoRaster.render(w.cmds, true)).minOf { it / W } }
        val n = EngoStyle.NUMBER
        val mixed = page(elements = listOf(reading("KM/H", "42", n), null,
            reading("BAT %", "76", n), reading("PWM %", "58"), reading("MOT°C", "58")))
        assertEquals(valueTop(mixed, "e4v"), valueTop(mixed, "e3v"))
        val numbers = page(elements = listOf(reading("KM/H", "42", n), null,
            reading("BAT %", "76", n), reading("PWM %", "58", n), reading("MOT°C", "58", n)))
        assertTrue(valueTop(numbers, "e3v") < valueTop(mixed, "e3v"))
    }

    @Test
    fun elementOneIsBigInEveryLayout() {
        for (layout in EngoLayoutKind.entries) {
            val p = page(layout, all(EngoStyle.NUMBER, "63"))
            val w = EngoLayout.widgets(p, engo3).first { it.id == "e1v" }
            val ys = lit(EngoRaster.render(w.cmds, color = true)).map { it / W }
            assertTrue("$layout element 1 tall", ys.max() - ys.min() + 1 >= 48)
        }
    }

    @Test
    fun theCockpitSpeedSitsInTheMiddle() {
        val w = EngoLayout.widgets(page(), engo3).first { it.id == "e1v" }
        val xs = lit(EngoRaster.render(w.cmds, color = true)).map { it % W }
        assertEquals(152.0, (xs.min() + xs.max()) / 2.0, 1.0)
    }

    @Test
    fun anUnchangedPageSendsNothing_andANewSpeedOnlyTheSpeed() {
        fun speed(v: String) = page(elements = listOf(reading("KM/H", v, EngoStyle.NUMBER), null, null, null, null))
        val a = EngoLayout.widgets(speed("42"), engo3)
        assertTrue(EngoLayout.delta(a, EngoLayout.widgets(speed("42"), engo3), engo3).isEmpty())

        val b = EngoLayout.widgets(speed("43"), engo3)
        val d = EngoLayout.delta(a, b, engo3)
        val full = EngoLayout.full(b)
        assertTrue("delta smaller than the page", d.sumOf { it.size } * 2 < full.sumOf { it.size })
        assertFalse("no clear in a delta", 0x01 in ops(d))
        val over = EngoRaster.render(d, true, EngoRaster.render(EngoLayout.full(a), true))
        assertArrayEquals(EngoRaster.render(full, true), over)
    }

    @Test
    fun aDeltaOverEveryChangeMatchesAWholeFrame() {
        // Every layout against itself with other values, and across layouts.
        val pages = EngoLayoutKind.entries.flatMap { l ->
            listOf(
                page(l, all(EngoStyle.GRAPH, "42", "7")),
                page(l, all(EngoStyle.GRAPH, "43", "8")),
                page(l, all(EngoStyle.SMOOTH, "9")),
                page(l, nav = EngoNav(EngoManeuver.RIGHT, "80 M")),
            )
        }
        for (from in pages) for (to in pages) {
            val a = EngoLayout.widgets(from, engo3)
            val b = EngoLayout.widgets(to, engo3)
            val over = EngoRaster.render(EngoLayout.delta(a, b, engo3), true, EngoRaster.render(EngoLayout.full(a), true))
            assertArrayEquals("${from.layout} -> ${to.layout}", EngoRaster.render(EngoLayout.full(b), true), over)
        }
    }

    @Test
    fun theFirstFrameIsWhole() {
        val d = EngoLayout.delta(null, EngoLayout.widgets(page(), engo3), engo3)
        assertTrue(0x01 in ops(d))
    }

    @Test
    fun graphsDrawPolylines_barsAndNumbersDoNot() {
        assertTrue(0x38 in ops(EngoLayout.render(page(elements = all(EngoStyle.GRAPH, "5")), engo3)))
        for (s in listOf(EngoStyle.BLOCKS, EngoStyle.SMOOTH, EngoStyle.NUMBER)) {
            assertFalse(s.name, 0x38 in ops(EngoLayout.render(page(elements = all(s, "5")), engo3)))
        }
    }

    @Test
    fun aPlaceWithoutRoomDrawsTheNumberAlone() {
        // The cockpit's big speed has no band for a bar: asking for a graph draws just the number.
        val p = page(elements = listOf(reading("KM/H", "42", EngoStyle.GRAPH)))
        val ids = EngoLayout.widgets(p, engo3).map { it.id }
        assertFalse("e1g" in ids)
        assertFalse(0x38 in ops(EngoLayout.render(p, engo3)))
    }

    @Test
    fun turnsFollowTheLayoutAndTheSwitch() {
        fun navPixels(p: EngoPage) = EngoLayout.widgets(p, engo3).filter { it.id.startsWith("nav") }
            .sumOf { lit(EngoRaster.render(it.cmds, true)).size }
        val n = EngoNav(EngoManeuver.LEFT, "120 M")
        for (l in EngoLayoutKind.entries) assertEquals("$l no route", 0, navPixels(page(l)))
        for (l in EngoLayoutKind.entries) assertTrue("$l route", navPixels(page(l, nav = n)) > 50)
        // Switch off: only Turn still shows the turn.
        for (l in EngoLayoutKind.entries) {
            val shown = navPixels(page(l, nav = n, turnsOn = false)) > 0
            assertEquals("$l switch off", l == EngoLayoutKind.TURN, shown)
        }
        for (m in EngoManeuver.entries) assertTrue("$m draws", navPixels(page(nav = EngoNav(m, "120 M"))) > 50)
    }

    @Test
    fun theOtherLayoutsGiveWayToTurnDuringARoute() {
        val n = EngoNav(EngoManeuver.LEFT, "120 M")
        for (l in listOf(EngoLayoutKind.BIG, EngoLayoutKind.QUAD, EngoLayoutKind.MINIMAL)) {
            assertEquals(EngoLayoutKind.TURN, EngoLayouts.resolve(l, navigating = true, turnsOn = true).kind)
            assertEquals(l, EngoLayouts.resolve(l, navigating = true, turnsOn = false).kind)
        }
        assertEquals(EngoLayoutKind.COCKPIT, EngoLayouts.resolve(EngoLayoutKind.COCKPIT, true, true).kind)
        // Turn with no route keeps element 1 big, as on Big.
        val idle = EngoLayouts.resolve(EngoLayoutKind.TURN, navigating = false, turnsOn = true)
        assertTrue(idle.spots.first { it.element == 0 }.geom is EngoLayouts.Geom.Hero)
        assertTrue(EngoLayout.widgets(page(EngoLayoutKind.MINIMAL, nav = n), engo3).any { it.id == "e3v" })
    }

    @Test
    fun elementCountsPerLayout() {
        assertEquals(5, EngoLayouts.elementsUsed(EngoLayoutKind.COCKPIT, true))
        assertEquals(4, EngoLayouts.elementsUsed(EngoLayoutKind.QUAD, true))
        assertEquals(3, EngoLayouts.elementsUsed(EngoLayoutKind.TURN, false))
        assertEquals(1, EngoLayouts.elementsUsed(EngoLayoutKind.MINIMAL, false))
        // Minimal sets up the Turn layout's elements too while it can take over.
        assertEquals(3, EngoLayouts.elementsUsed(EngoLayoutKind.MINIMAL, true))
        assertTrue(EngoLayouts.onlyDuringRoute(EngoLayoutKind.MINIMAL, 2))
        assertFalse(EngoLayouts.onlyDuringRoute(EngoLayoutKind.BIG, 2))
    }

    @Test
    fun engo2_onlyGrey_engo3_onlyPaletteColours() {
        for (p in worst) {
            val c2 = ops(EngoLayout.render(p, engo2))
            assertFalse(0x3D in c2)
            val c3 = EngoLayout.render(p, engo3)
            assertFalse(0x30 in ops(c3))
            c3.filter { (it[1].toInt() and 0xFF) == 0x3D }
                .forEach { assertTrue(EngoRaster.onPalette(it[4].toInt() and 0xFF)) }
        }
    }

    @Test
    fun pwmBandsColourTheBarAndTheNumber() {
        fun colourOf(band: EngoBand, id: String): Int {
            val p = page(elements = listOf(null, null, null, reading("PWM %", "90", EngoStyle.SMOOTH, 0.9f, band), null))
            val w = EngoLayout.widgets(p, engo3).first { it.id == id }
            return w.cmds.first { (it[1].toInt() and 0xFF) == 0x3D }[4].toInt() and 0xFF
        }
        for ((band, rg) in listOf(EngoBand.OK to EngoLayout.RG_OK, EngoBand.WARN to EngoLayout.RG_WARN,
                EngoBand.DANGER to EngoLayout.RG_DANGER)) {
            assertEquals(rg, colourOf(band, "e4v"))
            assertEquals(rg, colourOf(band, "e4g"))
        }
    }

    @Test
    fun resampleKeepsTheEnds() {
        val s = List(64) { it / 63f }
        val r = EngoLayout.resample(s, 43)
        assertEquals(43, r.size)
        assertEquals(0f, r.first(), 1e-6f)
        assertEquals(1f, r.last(), 1e-6f)
    }
}
