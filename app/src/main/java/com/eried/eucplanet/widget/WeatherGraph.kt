package com.eried.eucplanet.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The forecast curve, drawn to a bitmap for a widget to carry.
 *
 * A widget cannot draw. Its layout is inflated in the launcher's process, where
 * only the classic view set exists and no code of ours runs, so the only way to
 * put a chart on a home screen is to paint it here and ship the pixels through
 * `setImageViewBitmap`.
 *
 * That has a hard cost: RemoteViews carry a bitmap memory limit, and a widget
 * whose bitmaps exceed it is killed by the launcher rather than clipped. So the
 * canvas is sized from the cells the widget actually occupies and capped, and
 * the drawing stays flat colour and stroke, no shadows or layers.
 */
object WeatherGraph {

    /** Beyond this the launcher gains nothing and the RemoteViews bill grows
     *  with the square of it. A 4-cell-wide widget is ~1000 px on a Pixel. */
    private const val MAX_W = 1200
    private const val MAX_H = 500

    /** The score band ends, matching the panel's own red-to-green ramp. Widgets
     *  live outside the theme system (see [EucWidget]), so these are literals
     *  chosen to match the weatherBad / weatherGood token defaults. */
    private const val BAD = 0xFFE05A47.toInt()
    private const val GOOD = 0xFF5AB55A.toInt()
    private const val INK = 0xFF9AA0A6.toInt()

    fun colorFor(score: Float): Int {
        val t = ((score + 5f) / 10f).coerceIn(0f, 1f)
        fun mix(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt()
        return Color.rgb(
            mix(Color.red(BAD), Color.red(GOOD)),
            mix(Color.green(BAD), Color.green(GOOD)),
            mix(Color.blue(BAD), Color.blue(GOOD)),
        )
    }

    /**
     * Draws [series] across [wPx] x [hPx].
     *
     * [withHours] adds the clock labels along the bottom, which only earn their
     * room on the taller widget. Returns null when there is nothing to draw, so
     * the caller can show its "never refreshed" state instead of a blank box.
     */
    fun render(
        series: List<Float>,
        startMs: Long,
        stepMs: Long,
        wPx: Int,
        hPx: Int,
        withHours: Boolean,
    ): Bitmap? {
        if (series.size < 2) return null
        val w = wPx.coerceIn(120, MAX_W)
        val h = hPx.coerceIn(60, MAX_H)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val labelH = if (withHours) h * 0.20f else 0f
        val plotH = h - labelH
        // The band is fixed at -5..+5 rather than fitted to the window: a
        // widget is glanced at, and a curve that rescales itself would make a
        // mediocre afternoon look like a great one.
        val lo = -5f
        val hi = 5f
        fun y(v: Float) = plotH - ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * plotH * 0.88f - plotH * 0.06f
        fun x(i: Int) = w * i / (series.size - 1).toFloat()

        // The neutral line, so a rider can see which side of "fine" the curve
        // is on without reading a number.
        c.drawLine(
            0f, y(0f), w.toFloat(), y(0f),
            Paint().apply {
                color = INK
                alpha = 60
                strokeWidth = max(1f, h / 90f)
            },
        )

        val path = Path().apply {
            moveTo(x(0), y(series[0]))
            for (i in 1 until series.size) lineTo(x(i), y(series[i]))
        }

        // Fill under the curve, tinted by the score at each end, so the shape
        // reads even at the size of a phone icon.
        val fill = Path(path).apply {
            lineTo(x(series.size - 1), plotH)
            lineTo(x(0), plotH)
            close()
        }
        c.drawPath(
            fill,
            Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
                    colorFor(series.first()), colorFor(series.last()),
                    Shader.TileMode.CLAMP,
                )
                alpha = 60
            },
        )

        // The curve itself, segment by segment, each in its own score's colour.
        val stroke = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = max(2f, h / 26f)
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 1 until series.size) {
            stroke.color = colorFor((series[i - 1] + series[i]) / 2f)
            c.drawLine(x(i - 1), y(series[i - 1]), x(i), y(series[i]), stroke)
        }

        // Where "now" is: the left edge, marked so the curve reads as a future.
        c.drawCircle(
            x(0), y(series[0]), max(3f, h / 20f),
            Paint().apply {
                isAntiAlias = true
                color = colorFor(series[0])
            },
        )

        if (withHours && stepMs > 0L) {
            val fmt = SimpleDateFormat("HH", Locale.getDefault())
            val text = Paint().apply {
                isAntiAlias = true
                color = INK
                textSize = max(9f, labelH * 0.62f)
                textAlign = Paint.Align.CENTER
            }
            // Four labels at most: more than that and they collide on a
            // two-cell widget, and the rider is reading a shape, not a table.
            val ticks = min(4, series.size)
            for (t in 0 until ticks) {
                val i = (series.size - 1) * t / max(1, ticks - 1)
                val at = startMs + stepMs * i
                val tx = x(i).coerceIn(text.textSize, w - text.textSize)
                c.drawText(fmt.format(Date(at)), tx, h - labelH * 0.15f, text)
            }
        }
        return bmp
    }
}
