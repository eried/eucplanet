package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

actual class CanvasTextPaint actual constructor(color: Color, sizePx: Float, align: CanvasTextAlign) {
    internal val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        isAntiAlias = true
        when (align) {
            CanvasTextAlign.LEFT -> Unit
            CanvasTextAlign.CENTER -> textAlign = android.graphics.Paint.Align.CENTER
            CanvasTextAlign.RIGHT -> textAlign = android.graphics.Paint.Align.RIGHT
        }
    }

    actual fun measureText(text: String): Float = paint.measureText(text)
}

actual fun DrawScope.drawCanvasText(text: String, x: Float, y: Float, paint: CanvasTextPaint) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint.paint)
}
