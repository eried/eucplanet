package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

actual fun DrawScope.drawRadarDistanceLabel(text: String, x: Float, y: Float, textSize: Float) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        this.textSize = textSize
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        text, x, y + paint.textSize * 0.35f, paint
    )
}

actual fun DrawScope.drawMapCredit(full: String, short: String) {
    val textSize = (size.minDimension * 0.055f).coerceIn(9f, 22f)
    val pad = textSize * 0.35f
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.textSize = textSize
        color = android.graphics.Color.WHITE
        // A shadow rather than a plate: it stays legible over
        // satellite and over pale topo without boxing off the map.
        setShadowLayer(textSize * 0.35f, 0f, 0f, android.graphics.Color.BLACK)
    }
    // A clipped credit credits nobody, and a map element is often a
    // thumbnail. Use the full string when it fits, the short one
    // when it does not, and shrink the type only as a last resort.
    val room = size.width - pad * 2
    var text = full
    if (paint.measureText(text) > room) text = short
    while (paint.measureText(text) > room && paint.textSize > 7f) {
        paint.textSize = paint.textSize - 0.5f
    }
    val width = paint.measureText(text)
    drawContext.canvas.nativeCanvas.drawText(
        text,
        (size.width - width - pad).coerceAtLeast(pad),
        size.height - pad,
        paint
    )
}

actual fun colorToHsv(argb: Int): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
