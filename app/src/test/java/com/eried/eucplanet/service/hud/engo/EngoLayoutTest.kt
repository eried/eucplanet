package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ENGO ride page as drawn: batching, the delta that redraws only what
 * changed, and, through [EngoRaster], where the pixels actually land: inside
 * each widget's own box, never on a neighbour, numbers centred in their column.
 */
class EngoLayoutTest {

    private val engo2 = EngoCaps(colorRYG = false)
    private val engo3 = EngoCaps(colorRYG = true)
    private val W = EngoLayout.W
    private val H = EngoLayout.H

    private fun reading(title: String, value: String, level: Float = 0.5f, band: EngoBand = EngoBand.NORMAL) =
        EngoReading(title, value, level, List(43) { (it % 10) / 10f }, band)

    private fun page(
        speed: String = "42",
        graphs: Boolean = false,
        nav: EngoNav? = null,
        top: EngoReading? = reading("TOP", "87"),
        bottom: List<EngoReading?> = listOf(
            reading("BAT %", "76"), reading("PWM %", "58", 0.58f, EngoBand.OK), reading("MOT°C", "58"),
        ),
    ) = EngoPage(true, speed, "KM/H", top, bottom, graphs, nav)

    /** The worst the page has to hold: three-digit speed, long titles, wide numbers. */
    private val worst = listOf(
        page(),
        page(speed = "188", graphs = true, nav = EngoNav(EngoManeuver.UTURN, "1.2 KM")),
        page(speed = "--", top = reading("MAX PWM", "100"), bottom = listOf(
            reading("PK MOT W", "-2350"), reading("RNG KM", "123.4"), reading("PHONE %", "100"))),
        page(top = null, bottom = listOf(null, null, null), nav = EngoNav(EngoManeuver.ARRIVE, "")),
    )

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
    fun noTextCommands_thePageDrawsItsOwnFont() {
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
                    assertTrue("${w.id} pixel ($x,$y) outside its box",
                        w.boxes.any { x in it[0]..it[2] && y in it[1]..it[3] })
                }
            }
            val boxes = ws.flatMap { w -> w.boxes.map { w.id to it } }
            for (a in boxes.indices) for (b in a + 1 until boxes.size) {
                val (ia, ra) = boxes[a]; val (ib, rb) = boxes[b]
                val apart = ra[2] < rb[0] || rb[2] < ra[0] || ra[3] < rb[1] || rb[3] < ra[1]
                assertTrue("$ia and $ib boxes overlap", apart)
            }
            // Erasing a box must never wipe the corner marks or the dividers.
            for ((id, r) in boxes) for (y in r[1]..r[3]) for (x in r[0]..r[2]) {
                assertFalse("$id box covers decoration at ($x,$y)", decor[y * W + x])
            }
        }
    }

    @Test
    fun everythingStaysOnThePanel() {
        for (p in worst) for ((_, r) in EngoLayout.widgets(p, engo3).flatMap { w -> w.boxes.map { w.id to it } }) {
            assertTrue(r[0] >= 0 && r[1] >= 0 && r[2] < W && r[3] < H)
        }
    }

    @Test
    fun bottomNumbersAreCentredInTheirColumns() {
        val ws = EngoLayout.widgets(page(), engo3)
        for (i in 0 until 3) {
            val w = ws.first { it.id == "v$i" }
            val xs = lit(EngoRaster.render(w.cmds, color = true)).map { it % W }
            val box = w.boxes.single()
            val left = xs.min() - box[0]
            val right = box[2] - xs.max()
            assertTrue("v$i off centre: $left vs $right", kotlin.math.abs(left - right) <= 1)
        }
    }

    @Test
    fun theSpeedIsCentredAndBig() {
        val w = EngoLayout.widgets(page(speed = "63"), engo3).first { it.id == "speed" }
        val px = lit(EngoRaster.render(w.cmds, color = true))
        val ys = px.map { it / W }
        assertTrue("speed tall", ys.max() - ys.min() + 1 >= 60)
        val xs = px.map { it % W }
        assertEquals(152.0, (xs.min() + xs.max()) / 2.0, 1.0)
    }

    @Test
    fun anUnchangedPageSendsNothing_andANewSpeedOnlyTheSpeed() {
        val a = EngoLayout.widgets(page(speed = "42"), engo3)
        assertTrue(EngoLayout.delta(a, EngoLayout.widgets(page(speed = "42"), engo3), engo3).isEmpty())

        val b = EngoLayout.widgets(page(speed = "43"), engo3)
        val d = EngoLayout.delta(a, b, engo3)
        val full = EngoLayout.full(b)
        assertTrue("delta smaller than the page", d.sumOf { it.size } * 4 < full.sumOf { it.size })
        assertFalse("no clear in a delta", 0x01 in ops(d))
        // Drawing the delta over the old page gives the new page, pixel for pixel.
        val over = EngoRaster.render(d, true, EngoRaster.render(EngoLayout.full(a), true))
        assertArrayEquals(EngoRaster.render(full, true), over)
    }

    @Test
    fun aDeltaOverEveryChangeMatchesAWholeFrame() {
        for (from in worst) for (to in worst) {
            val a = EngoLayout.widgets(from, engo3)
            val b = EngoLayout.widgets(to, engo3)
            val over = EngoRaster.render(EngoLayout.delta(a, b, engo3), true, EngoRaster.render(EngoLayout.full(a), true))
            assertArrayEquals(EngoRaster.render(EngoLayout.full(b), true), over)
        }
    }

    @Test
    fun theFirstFrameIsWhole() {
        val d = EngoLayout.delta(null, EngoLayout.widgets(page(), engo3), engo3)
        assertTrue(0x01 in ops(d))
    }

    @Test
    fun graphsDrawPolylines_barsDrawSegments() {
        assertTrue(0x38 in ops(EngoLayout.render(page(graphs = true), engo3)))
        assertFalse(0x38 in ops(EngoLayout.render(page(graphs = false), engo3)))
    }

    @Test
    fun navCornerOnlyWhileNavigating() {
        fun navPixels(p: EngoPage) = EngoLayout.widgets(p, engo3).filter { it.id.startsWith("nav") }
            .sumOf { lit(EngoRaster.render(it.cmds, true)).size }
        assertEquals(0, navPixels(page(nav = null)))
        for (m in EngoManeuver.entries) assertTrue("$m draws", navPixels(page(nav = EngoNav(m, "120 M"))) > 50)
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
        fun colourOf(band: EngoBand): Int {
            val p = page(bottom = listOf(null, reading("PWM %", "90", 0.9f, band), null))
            val w = EngoLayout.widgets(p, engo3).first { it.id == "v1" }
            return w.cmds.first { (it[1].toInt() and 0xFF) == 0x3D }[4].toInt() and 0xFF
        }
        assertEquals(EngoLayout.RG_OK, colourOf(EngoBand.OK))
        assertEquals(EngoLayout.RG_WARN, colourOf(EngoBand.WARN))
        assertEquals(EngoLayout.RG_DANGER, colourOf(EngoBand.DANGER))
    }
}
