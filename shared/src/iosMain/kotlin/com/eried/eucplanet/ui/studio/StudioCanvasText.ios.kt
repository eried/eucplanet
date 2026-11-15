package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface

// The same text Android stamps with its Paint, drawn with Skia on the native
// canvas: the system font, white, bold for the radar distances, a soft black
// shadow under the map credit.

private const val WHITE = -0x1 // 0xFFFFFFFF
private const val BLACK = -0x1000000 // 0xFF000000

private val regular: Typeface? by lazy { FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL) }
private val bold: Typeface? by lazy { FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD) }

actual fun DrawScope.drawRadarDistanceLabel(text: String, x: Float, y: Float, textSize: Float) {
    val font = Font(bold, textSize)
    val line = TextLine.make(text, font)
    val paint = Paint().apply {
        color = WHITE
        isAntiAlias = true
    }
    // Centred on x like Paint.Align.CENTER; the baseline sits 0.35 em under y, as on Android.
    drawContext.canvas.skiaCanvas.drawTextLine(line, x - line.width / 2f, y + textSize * 0.35f, paint)
}

actual fun DrawScope.drawMapCredit(full: String, short: String) {
    var textSize = (size.minDimension * 0.055f).coerceIn(9f, 22f)
    val pad = textSize * 0.35f
    // Android's setShadowLayer takes a blur radius; Skia's drop shadow a sigma, about half of it.
    val paint = Paint().apply {
        isAntiAlias = true
        color = WHITE
        imageFilter = ImageFilter.makeDropShadow(0f, 0f, textSize * 0.35f / 2f, textSize * 0.35f / 2f, BLACK, null, null)
    }
    fun width(t: String, s: Float) = TextLine.make(t, Font(regular, s)).width
    // A clipped credit credits nobody, and a map element is often a
    // thumbnail. Use the full string when it fits, the short one
    // when it does not, and shrink the type only as a last resort.
    val room = size.width - pad * 2
    var text = full
    if (width(text, textSize) > room) text = short
    while (width(text, textSize) > room && textSize > 7f) textSize -= 0.5f
    val line = TextLine.make(text, Font(regular, textSize))
    drawContext.canvas.skiaCanvas.drawTextLine(
        line,
        (size.width - line.width - pad).coerceAtLeast(pad),
        size.height - pad,
        paint,
    )
}

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
