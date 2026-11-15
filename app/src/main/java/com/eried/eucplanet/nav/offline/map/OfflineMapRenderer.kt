package com.eried.eucplanet.nav.offline.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import com.eried.eucplanet.nav.offline.JvmOfflineFiles
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Draws map pictures from the downloaded squares, for every map in the app:
 * the Leaflet maps put them under the online map, and the HUD, the watch,
 * Android Auto and the video overlay use them when the online map fails.
 * What is drawn is the shared [OfflineMapDrawer]; this draws it on a Canvas.
 */
class OfflineMapRenderer(dir: String) {

    private val drawer = OfflineMapDrawer(dir, JvmOfflineFiles()) { PmTiles.open(File(it)) }

    /** The tile drawn [size] pixels wide, or null when no square has it. */
    fun render(z: Int, x: Int, y: Int, size: Int = 256): Bitmap? =
        drawer.render(z, x, y, size, { AndroidTileCanvas(Bitmap.createBitmap(it, it, Bitmap.Config.ARGB_8888)) }) { it.bitmap }

    /** [render] as PNG bytes. */
    fun png(z: Int, x: Int, y: Int, size: Int = 256): ByteArray? {
        val bmp = render(z, x, y, size) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    /** A name for a spot from the downloaded map, or null without one. */
    fun addressAt(lat: Double, lng: Double): String? = drawer.addressAt(lat, lng)

    /** Forgets open squares, after one is downloaded or deleted. */
    fun reset() = drawer.reset()
}

/** [TileCanvas] on an Android Canvas, with the paints the renderer always used. */
private class AndroidTileCanvas(val bitmap: Bitmap) : TileCanvas {
    private val c = Canvas(bitmap)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var dashFor = 0f
    private var dash: DashPathEffect? = null

    override val width get() = c.width
    override val height get() = c.height

    private class P : TilePath {
        val path = Path()
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun close() = path.close()
        override fun evenOdd() { path.fillType = Path.FillType.EVEN_ODD }
    }

    private val TilePath.path get() = (this as P).path

    override fun newPath(): TilePath = P()

    override fun drawColor(argb: Int) = c.drawColor(argb)

    override fun fill(path: TilePath, argb: Int) {
        fill.color = argb
        c.drawPath(path.path, fill)
    }

    override fun stroke(path: TilePath, argb: Int, width: Float, dash: Float) {
        stroke.color = argb
        stroke.strokeWidth = width
        stroke.pathEffect = if (dash > 0f) dashEffect(dash) else null
        c.drawPath(path.path, stroke)
    }

    private fun dashEffect(on: Float): DashPathEffect {
        if (dash == null || dashFor != on) {
            dash = DashPathEffect(floatArrayOf(on, on), 0f)
            dashFor = on
        }
        return dash!!
    }

    private fun apply(style: TileText) {
        text.textSize = style.size
        text.typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.color = style.argb
        text.textAlign = if (style.centered) Paint.Align.CENTER else Paint.Align.LEFT
        halo.textSize = style.size
        halo.typeface = text.typeface
        halo.textAlign = text.textAlign
        halo.color = style.haloArgb
        halo.strokeWidth = style.haloWidth
        halo.strokeJoin = if (style.roundHalo) Paint.Join.ROUND else Paint.Join.MITER
    }

    override fun measureText(text: String, style: TileText): Float {
        apply(style)
        return this.text.measureText(text)
    }

    override fun drawText(text: String, x: Float, y: Float, style: TileText) {
        apply(style)
        c.drawText(text, x, y, halo)
        c.drawText(text, x, y, this.text)
    }

    override fun drawTextOnPath(text: String, path: TilePath, hOffset: Float, vOffset: Float, style: TileText) {
        apply(style)
        c.drawTextOnPath(text, path.path, hOffset, vOffset, halo)
        c.drawTextOnPath(text, path.path, hOffset, vOffset, this.text)
    }

    override fun length(path: TilePath): Float = PathMeasure(path.path, false).length

    override fun bounds(path: TilePath): FloatArray {
        val b = RectF()
        path.path.computeBounds(b, true)
        return floatArrayOf(b.left, b.top, b.right, b.bottom)
    }
}
