package com.eried.eucplanet.ui.studio

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The studio's map credit and radar distances are Skia text on the iPhone.
 * The unnamed default font drew no glyphs in the offline map; these pin that
 * the studio's text puts ink on the canvas.
 */
class StudioCanvasTextTest {

    private fun inked(w: Int, h: Int, block: DrawScope.() -> Unit): Int {
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat()), block)
        val px = img.toPixelMap()
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (px[x, y].alpha > 0.5f) n++
        return n
    }

    @Test fun theMapCreditIsDrawn() {
        assertTrue(inked(300, 60) { drawMapCredit("(c) OpenStreetMap contributors", "(c) OSM") } > 50)
    }

    @Test fun aRadarDistanceIsDrawn() {
        assertTrue(inked(120, 60) { drawRadarDistanceLabel("42 m", 60f, 30f, 20f) } > 30)
    }
}
