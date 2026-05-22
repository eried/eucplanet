package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// One digit cell is 2 x 3.6 units; a colon cell is 0.9 wide; cells sit GAP apart.
private const val CELL_W = 2f
private const val CELL_H = 3.6f
private const val COLON_W = 0.9f
private const val DOT_W = 0.7f
private const val GAP = 0.42f

/**
 * A 7-segment numeric display. [text] may hold digits 0-9, ':' and '.'. Unlit
 * segments are drawn faintly so it reads like a real LED panel.
 */
@Composable
fun SevenSegmentDisplay(text: String, color: Color, modifier: Modifier = Modifier) {
    var widthUnits = 0f
    text.forEach {
        widthUnits += (when (it) {
            ':' -> COLON_W
            '.' -> DOT_W
            else -> CELL_W
        }) + GAP
    }
    widthUnits = (widthUnits - GAP).coerceAtLeast(CELL_W)
    Canvas(modifier.fillMaxWidth().aspectRatio(widthUnits / CELL_H)) {
        val unit = size.height / CELL_H
        var x = 0f
        text.forEach { ch ->
            when (ch) {
                ':' -> {
                    drawColon(x * unit, unit, color)
                    x += COLON_W + GAP
                }
                '.' -> {
                    drawDot(x * unit, unit, color)
                    x += DOT_W + GAP
                }
                in '0'..'9' -> {
                    drawDigit(ch - '0', x * unit, unit, color)
                    x += CELL_W + GAP
                }
                else -> x += CELL_W + GAP
            }
        }
    }
}

// Segment order: a(top) b(top-right) c(bottom-right) d(bottom) e(bottom-left)
// f(top-left) g(middle).
private val SEGMENTS = arrayOf(
    booleanArrayOf(true, true, true, true, true, true, false),    // 0
    booleanArrayOf(false, true, true, false, false, false, false),// 1
    booleanArrayOf(true, true, false, true, true, false, true),   // 2
    booleanArrayOf(true, true, true, true, false, false, true),   // 3
    booleanArrayOf(false, true, true, false, false, true, true),  // 4
    booleanArrayOf(true, false, true, true, false, true, true),   // 5
    booleanArrayOf(true, false, true, true, true, true, true),    // 6
    booleanArrayOf(true, true, true, false, false, false, false), // 7
    booleanArrayOf(true, true, true, true, true, true, true),     // 8
    booleanArrayOf(true, true, true, true, false, true, true)     // 9
)

private fun DrawScope.drawDigit(digit: Int, ox: Float, unit: Float, color: Color) {
    val segs = SEGMENTS[digit]
    val w = CELL_W * unit
    val h = CELL_H * unit
    val t = 0.30f * unit
    val pad = t * 0.85f
    val left = pad
    val right = w - pad
    val top = pad
    val bot = h - pad
    val mid = h / 2f
    val lit = color
    val dim = color.copy(alpha = 0.12f)
    fun seg(on: Boolean, ax: Float, ay: Float, bx: Float, by: Float) {
        drawLine(
            color = if (on) lit else dim,
            start = Offset(ox + ax, ay),
            end = Offset(ox + bx, by),
            strokeWidth = t,
            cap = StrokeCap.Round
        )
    }
    seg(segs[0], left, top, right, top)     // a
    seg(segs[1], right, top, right, mid)    // b
    seg(segs[2], right, mid, right, bot)    // c
    seg(segs[3], left, bot, right, bot)     // d
    seg(segs[4], left, mid, left, bot)      // e
    seg(segs[5], left, top, left, mid)      // f
    seg(segs[6], left, mid, right, mid)     // g
}

private fun DrawScope.drawColon(ox: Float, unit: Float, color: Color) {
    val cx = ox + COLON_W * unit / 2f
    val r = 0.17f * unit
    drawCircle(color, r, Offset(cx, CELL_H * unit * 0.36f))
    drawCircle(color, r, Offset(cx, CELL_H * unit * 0.66f))
}

/** A decimal point — a single dot resting near the baseline of a digit cell. */
private fun DrawScope.drawDot(ox: Float, unit: Float, color: Color) {
    val cx = ox + DOT_W * unit / 2f
    val r = 0.2f * unit
    drawCircle(color, r, Offset(cx, CELL_H * unit - r * 1.4f))
}

/** A clean, modern analog clock face. */
@Composable
fun AnalogClock(
    hour: Int,
    minute: Int,
    second: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color.copy(alpha = 0.85f), r - r * 0.045f, c, style = Stroke(r * 0.05f))
        for (i in 0 until 12) {
            val ang = Math.toRadians(i * 30.0)
            val outer = r * 0.88f
            val inner = if (i % 3 == 0) r * 0.73f else r * 0.81f
            drawLine(
                color.copy(alpha = 0.7f),
                Offset(c.x + (sin(ang) * inner).toFloat(), c.y - (cos(ang) * inner).toFloat()),
                Offset(c.x + (sin(ang) * outer).toFloat(), c.y - (cos(ang) * outer).toFloat()),
                strokeWidth = if (i % 3 == 0) r * 0.05f else r * 0.022f,
                cap = StrokeCap.Round
            )
        }
        fun hand(angleDeg: Float, length: Float, width: Float, col: Color) {
            val ang = Math.toRadians(angleDeg.toDouble())
            drawLine(
                col, c,
                Offset(c.x + (sin(ang) * length).toFloat(), c.y - (cos(ang) * length).toFloat()),
                strokeWidth = width,
                cap = StrokeCap.Round
            )
        }
        hand((hour % 12 + minute / 60f) * 30f, r * 0.48f, r * 0.072f, color)
        hand((minute + second / 60f) * 6f, r * 0.72f, r * 0.05f, color)
        hand(second * 6f, r * 0.80f, r * 0.024f, color.copy(alpha = 0.85f))
        drawCircle(color, r * 0.06f, c)
    }
}
