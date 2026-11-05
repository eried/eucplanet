package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

enum class CanvasTextAlign { LEFT, CENTER, RIGHT }

/**
 * Text on a Compose Canvas the way android.graphics.Paint draws it: [x] is the
 * anchor for [align] and y the baseline. Android uses Paint itself, so the
 * charts that used it draw the same pixels; iOS uses Skia's Font.
 */
expect class CanvasTextPaint(color: Color, sizePx: Float, align: CanvasTextAlign = CanvasTextAlign.LEFT) {
    fun measureText(text: String): Float
}

expect fun DrawScope.drawCanvasText(text: String, x: Float, y: Float, paint: CanvasTextPaint)
