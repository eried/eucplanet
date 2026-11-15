package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.drawscope.DrawScope

// Phase 2 draws these with Skia (org.jetbrains.skia.Font / TextLine on the
// native canvas). The studio is not shown on the iPhone yet.

actual fun DrawScope.drawRadarDistanceLabel(text: String, x: Float, y: Float, textSize: Float) = Unit

actual fun DrawScope.drawMapCredit(full: String, short: String) = Unit

/** Skia's SkRGBToHSV, which Android's Color.colorToHSV runs. */
actual fun colorToHsv(argb: Int): FloatArray {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val v = max / 255f
    if (delta == 0) return floatArrayOf(0f, 0f, v)
    val s = delta.toFloat() / max
    val dr = (max - r).toFloat() / delta
    val dg = (max - g).toFloat() / delta
    val db = (max - b).toFloat() / delta
    var h = when (max) {
        r -> if (g == min) 5 + db else 1 - dg
        g -> if (b == min) 1 + dr else 3 - db
        else -> if (r == min) 3 + dg else 5 - dr
    } * 60f
    if (h >= 360f) h -= 360f
    return floatArrayOf(h, s, v)
}
