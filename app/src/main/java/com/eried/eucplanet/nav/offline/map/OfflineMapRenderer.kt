package com.eried.eucplanet.nav.offline.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Draws map pictures from the downloaded squares, for every map in the app:
 * the Leaflet maps put them under the online map, and the HUD, the watch,
 * Android Auto and the video overlay use them when the online map fails.
 */
class OfflineMapRenderer(private val dir: File) {

    private companion object {
        const val TAG = "OfflineMap"
        /** How many zooms a tile may be drawn from above it: past this the
         *  scaled-up geometry is too coarse to be worth showing. */
        const val MAX_UP = 7
    }

    private class Open(val stamp: Long, val pm: PmTiles?)

    private val squares = HashMap<String, Open>()
    private val decoded = object : LinkedHashMap<String, VectorTile>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VectorTile>?) = size > 48
    }

    /** True when a downloaded square holds this tile. */
    fun has(z: Int, x: Int, y: Int): Boolean = square(z, x, y) != null

    /** The tile drawn [size] pixels wide, or null when no square has it. */
    fun render(z: Int, x: Int, y: Int, size: Int = 256): Bitmap? {
        val pm = square(z, x, y) ?: return null
        val layers = HashMap<String, Source>()
        for (name in OfflineMapStyle.LAYERS) {
            val src = source(pm, name, z, x, y, size) ?: continue
            layers[name] = src
        }
        if (layers.isEmpty()) return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), layers, z, size)
        return bmp
    }

    /** [render] as PNG bytes. */
    fun png(z: Int, x: Int, y: Int, size: Int = 256): ByteArray? {
        val bmp = render(z, x, y, size) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    /** A name for a spot from the downloaded map, or null without one. */
    fun addressAt(lat: Double, lng: Double): String? {
        val pm = open(com.eried.eucplanet.nav.offline.RoutingTiles.nameFor(lat, lng)) ?: return null
        return runCatching { OfflineAddress.at(pm, lat, lng) }.getOrNull()
    }

    /** Forgets open squares, after one is downloaded or deleted. */
    fun reset() = synchronized(squares) {
        squares.values.forEach { runCatching { it.pm?.close() } }
        squares.clear()
        synchronized(decoded) { decoded.clear() }
    }

    /** One layer from the tile itself or the ancestor it is stored at, with
     *  the scale and shift that map its units onto this tile's pixels. */
    private class Source(val layer: VectorTile.Layer, val scale: Float, val dx: Float, val dy: Float)

    private fun source(pm: PmTiles, name: String, z: Int, x: Int, y: Int, size: Int): Source? {
        // Outside towns a square stops before its deepest zooms: draw from
        // the nearest ancestor it has.
        var sz = min(z, min(pm.maxZoom, OfflineMapStyle.LAST_ZOOM[name] ?: z))
        var vt: VectorTile? = null
        while (sz >= 0 && z - sz <= MAX_UP) {
            vt = vector(pm, sz, x shr (z - sz), y shr (z - sz))
            if (vt != null) break
            sz--
        }
        if (vt == null) return null
        val dz = z - sz
        val ax = x shr dz
        val ay = y shr dz
        val layer = vt.layers[name] ?: return null
        val span = (1 shl dz).toFloat()
        val scale = size * span / layer.extent
        return Source(layer, scale, -(x - (ax shl dz)) * size.toFloat(), -(y - (ay shl dz)) * size.toFloat())
    }

    private fun vector(pm: PmTiles, z: Int, x: Int, y: Int): VectorTile? {
        val key = "${System.identityHashCode(pm)}/$z/$x/$y"
        synchronized(decoded) { decoded[key]?.let { return it } }
        val bytes = runCatching { pm.tile(z, x, y) }.onFailure { Log.w(TAG, "read $z/$x/$y: $it") }.getOrNull() ?: return null
        val vt = runCatching { VectorTile.decode(bytes) }.onFailure { Log.w(TAG, "decode $z/$x/$y: $it") }.getOrNull() ?: return null
        synchronized(decoded) { decoded[key] = vt }
        return vt
    }

    private fun square(z: Int, x: Int, y: Int): PmTiles? {
        // A tile across a square's edge is in both squares; a wide one, in
        // any square under it.
        val names = TileMath.squaresUnder(z, x, y)
        for (name in if (z <= 8) names else names.take(1)) open(name)?.let { return it }
        return null
    }

    private fun open(name: String): PmTiles? = synchronized(squares) {
        val f = File(dir, "$name.pmtiles")
        val stamp = if (f.isFile) f.lastModified() xor f.length() else 0L
        val have = squares[name]
        if (have != null && have.stamp == stamp) return have.pm
        have?.pm?.let { runCatching { it.close() } }
        val pm = if (stamp == 0L) null else runCatching { PmTiles(f) }.onFailure { Log.w(TAG, "$name: $it") }.getOrNull()
        squares[name] = Open(stamp, pm)
        pm
    }

    private fun path(f: VectorTile.Feature, s: Source, close: Boolean): Path {
        val p = Path()
        for (ring in f.rings) {
            if (ring.size < 4) continue
            p.moveTo(ring[0] * s.scale + s.dx, ring[1] * s.scale + s.dy)
            var i = 2
            while (i < ring.size) {
                p.lineTo(ring[i] * s.scale + s.dx, ring[i + 1] * s.scale + s.dy)
                i += 2
            }
            if (close) p.close()
        }
        p.fillType = Path.FillType.EVEN_ODD
        return p
    }

    private fun draw(c: Canvas, layers: Map<String, Source>, z: Int, size: Int) {
        val k = size / 256f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.drawColor(OfflineMapStyle.WATER)

        layers["earth"]?.let { s ->
            fill.color = OfflineMapStyle.LAND
            for (f in s.layer.features) if (f.type == VectorTile.POLYGON) c.drawPath(path(f, s, true), fill)
        }
        layers["landuse"]?.let { s ->
            for (f in s.layer.features) {
                if (f.type != VectorTile.POLYGON) continue
                fill.color = OfflineMapStyle.landuse(f.str("kind")) ?: continue
                c.drawPath(path(f, s, true), fill)
            }
        }
        layers["water"]?.let { s ->
            fill.color = OfflineMapStyle.WATER
            stroke.color = OfflineMapStyle.WATER
            stroke.pathEffect = null
            for (f in s.layer.features) {
                when (f.type) {
                    VectorTile.POLYGON -> c.drawPath(path(f, s, true), fill)
                    VectorTile.LINE -> {
                        stroke.strokeWidth = (OfflineMapStyle.waterLine(f.str("kind"), z) ?: continue) * k
                        c.drawPath(path(f, s, false), stroke)
                    }
                }
            }
        }
        if (z >= 14) layers["buildings"]?.let { s ->
            fill.color = OfflineMapStyle.BUILDING
            stroke.color = OfflineMapStyle.BUILDING_EDGE
            stroke.strokeWidth = 0.6f * k
            stroke.pathEffect = null
            for (f in s.layer.features) {
                if (f.type != VectorTile.POLYGON) continue
                val p = path(f, s, true)
                c.drawPath(p, fill)
                if (z >= 16) c.drawPath(p, stroke)
            }
        }
        val roads = layers["roads"]?.let { s ->
            s.layer.features.filter { it.type == VectorTile.LINE }.mapNotNull { f ->
                OfflineMapStyle.road(f.str("kind"), f.str("kind_detail"), z)?.let { Triple(f, it, path(f, s, false)) }
            }.sortedBy { it.second.order }
        }.orEmpty()
        val dash = DashPathEffect(floatArrayOf(2.5f * k, 2.5f * k), 0f)
        stroke.pathEffect = null
        for ((_, look, p) in roads) {
            val casing = look.casing ?: continue
            if (look.width * k < 1.5f) continue
            stroke.color = casing
            stroke.strokeWidth = look.width * k + 1.5f * k
            c.drawPath(p, stroke)
        }
        for ((_, look, p) in roads) {
            stroke.color = look.color
            stroke.strokeWidth = look.width * k
            stroke.pathEffect = if (look.dash) dash else null
            c.drawPath(p, stroke)
        }
        stroke.pathEffect = null

        labels(c, layers, roads, z, k)
    }

    private fun labels(
        c: Canvas,
        layers: Map<String, Source>,
        roads: List<Triple<VectorTile.Feature, OfflineMapStyle.Line, Path>>,
        z: Int,
        k: Float,
    ) {
        val taken = ArrayList<RectF>()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = OfflineMapStyle.TEXT }
        val halo = Paint(text).apply {
            style = Paint.Style.STROKE
            color = OfflineMapStyle.HALO
            strokeJoin = Paint.Join.ROUND
        }
        fun place(label: String, x: Float, y: Float, sizePx: Float, bold: Boolean, color: Int): Boolean {
            text.textSize = sizePx
            text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = color
            halo.textSize = sizePx
            halo.typeface = text.typeface
            halo.strokeWidth = sizePx / 5f
            val w = text.measureText(label)
            val r = RectF(x - w / 2 - 2, y - sizePx, x + w / 2 + 2, y + sizePx * 0.3f)
            if (r.left < 0 || r.top < 0 || r.right > c.width || r.bottom > c.height) return false
            if (taken.any { RectF.intersects(it, r) }) return false
            taken += r
            c.drawText(label, x, y, halo)
            c.drawText(label, x, y, text)
            return true
        }

        layers["places"]?.let { s ->
            s.layer.features.filter { it.type == VectorTile.POINT && it.str("name") != null }
                .filter { z >= OfflineMapStyle.placeMinZoom(it.str("kind"), it.str("kind_detail")) }
                .sortedBy { OfflineMapStyle.placeMinZoom(it.str("kind"), it.str("kind_detail")) }
                .forEach { f ->
                    val ring = f.rings.firstOrNull() ?: return@forEach
                    val big = OfflineMapStyle.placeMinZoom(f.str("kind"), f.str("kind_detail")) <= 9
                    place(f.str("name")!!, ring[0] * s.scale + s.dx, ring[1] * s.scale + s.dy, (if (big) 14f else 11.5f) * k, big, OfflineMapStyle.TEXT)
                }
        }

        if (z >= 15) {
            text.textSize = 10.5f * k
            text.typeface = Typeface.DEFAULT
            text.color = OfflineMapStyle.TEXT
            text.textAlign = Paint.Align.LEFT
            halo.set(text)
            halo.style = Paint.Style.STROKE
            halo.color = OfflineMapStyle.HALO
            halo.strokeWidth = 2.5f * k
            val done = HashSet<String>()
            for ((f, look, _) in roads.asReversed()) {
                val name = f.str("name") ?: continue
                if (look.order < 3 || !done.add(name)) continue
                val ring = f.rings.maxByOrNull { it.size } ?: continue
                val forward = ring[0] <= ring[ring.size - 2]
                val p = Path()
                val src = layers["roads"]!!
                var i = if (forward) 0 else ring.size - 2
                p.moveTo(ring[i] * src.scale + src.dx, ring[i + 1] * src.scale + src.dy)
                while (true) {
                    i += if (forward) 2 else -2
                    if (i < 0 || i >= ring.size) break
                    p.lineTo(ring[i] * src.scale + src.dx, ring[i + 1] * src.scale + src.dy)
                }
                val len = PathMeasure(p, false).length
                val w = text.measureText(name)
                if (len < w + 24 * k) continue
                val b = RectF()
                p.computeBounds(b, true)
                if (b.right < 0 || b.bottom < 0 || b.left > c.width || b.top > c.height) continue
                val off = (len - w) / 2
                c.drawTextOnPath(name, p, off, text.textSize * 0.35f, halo)
                c.drawTextOnPath(name, p, off, text.textSize * 0.35f, text)
            }
        }

        if (z >= 17) layers["buildings"]?.let { s ->
            for (f in s.layer.features) {
                val n = f.str("addr_housenumber") ?: continue
                val ring = f.rings.firstOrNull() ?: continue
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var i = 0
                while (i < ring.size) {
                    val px = ring[i] * s.scale + s.dx
                    val py = ring[i + 1] * s.scale + s.dy
                    minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
                    i += 2
                }
                place(n, (minX + maxX) / 2, (minY + maxY) / 2 + 4 * k, 9.5f * k, false, OfflineMapStyle.NUMBER)
            }
        }
    }
}
