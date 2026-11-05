package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine

actual class CanvasTextPaint actual constructor(color: Color, sizePx: Float, private val align: CanvasTextAlign) {
    internal val font = Font(null, sizePx)
    internal val paint = Paint().apply {
        this.color = color.toArgb()
        isAntiAlias = true
    }

    actual fun measureText(text: String): Float = TextLine.make(text, font).width

    internal fun startX(text: String, x: Float): Float = when (align) {
        CanvasTextAlign.LEFT -> x
        CanvasTextAlign.CENTER -> x - measureText(text) / 2f
        CanvasTextAlign.RIGHT -> x - measureText(text)
    }
}

actual fun DrawScope.drawCanvasText(text: String, x: Float, y: Float, paint: CanvasTextPaint) {
    drawContext.canvas.nativeCanvas.drawTextLine(TextLine.make(text, paint.font), paint.startX(text, x), y, paint.paint)
}
