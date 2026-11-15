package com.eried.eucplanet.app

import com.eried.eucplanet.nav.offline.map.TileText
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/** The iPhone's map drawing writes what Android's Canvas writes: labels too. */
class IosOfflineMapTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun inked(c: SkiaTileCanvas): Int {
        val bmp = Bitmap.makeFromImage(c.finish())
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (bmp.getColor(x, y) != white) n++
        return n
    }

    @Test fun aLabelIsDrawn() {
        val c = SkiaTileCanvas(256)
        c.drawColor(white)
        val style = TileText(14f, true, black, white, 2.8f, centered = true, roundHalo = true)
        assertTrue(c.measureText("Oslo", style) > 10f)
        c.drawText("Oslo", 128f, 128f, style)
        assertTrue(inked(c) > 50)
    }

    @Test fun aRoadNameFollowsItsRoad() {
        val c = SkiaTileCanvas(256)
        c.drawColor(white)
        val p = c.newPath().apply { moveTo(10f, 200f); lineTo(240f, 60f) }
        val style = TileText(10.5f, false, black, white, 2.5f, centered = false, roundHalo = false)
        assertTrue(c.length(p) > 200f)
        c.drawTextOnPath("Karl Johans gate", p, 20f, 3.7f, style)
        assertTrue(inked(c) > 50)
    }
}
