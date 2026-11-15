package com.eried.eucplanet.nav.offline.map

import btools.util.joinPath
import com.eried.eucplanet.nav.offline.OfflineFiles
import com.eried.eucplanet.nav.offline.RoutingTiles
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.LruMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.max
import kotlin.math.min

/**
 * The phone's drawing calls for one map tile: Android's Canvas and Paint
 * (OfflineMapRenderer), the iPhone's Skia (IosOfflineMap). Colours are ARGB.
 * Fills and strokes are anti-aliased; strokes have round caps and joins.
 */
interface TileCanvas {
    val width: Int
    val height: Int

    fun newPath(): TilePath

    /** Fills the whole tile. */
    fun drawColor(argb: Int)

    fun fill(path: TilePath, argb: Int)

    /** A line [width] wide; dashes [dash] on and [dash] off when above 0. */
    fun stroke(path: TilePath, argb: Int, width: Float, dash: Float)

    fun measureText(text: String, style: TileText): Float

    /** [text] with its halo under it, the baseline at [y]. */
    fun drawText(text: String, x: Float, y: Float, style: TileText)

    /** [text] along [path] from [hOffset], [vOffset] below it, halo first. */
    fun drawTextOnPath(text: String, path: TilePath, hOffset: Float, vOffset: Float, style: TileText)

    fun length(path: TilePath): Float

    /** left, top, right, bottom. */
    fun bounds(path: TilePath): FloatArray
}

/** A path on a [TileCanvas]; [evenOdd] fills its rings as holes. */
interface TilePath {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun close()
    fun evenOdd()
}

/**
 * How a label is drawn: [size] px, [bold], [argb] over a [haloArgb] halo
 * [haloWidth] wide. [centered] measures from the middle (else from the
 * left), and [roundHalo] gives the halo round joins (else mitred).
 */
data class TileText(
    val size: Float,
    val bold: Boolean,
    val argb: Int,
    val haloArgb: Int,
    val haloWidth: Float,
    val centered: Boolean,
    val roundHalo: Boolean,
)

/**
 * Draws map pictures from the downloaded squares in [dir], for every map
 * in the app: the Leaflet maps put them under the online map, and the HUD,
 * the watch, Android Auto and the video overlay use them when the online
 * map fails. The phone draws through a [TileCanvas]; [open] reads a square
 * (PmTiles.openPath unless the phone has its own reader).
 */
class OfflineMapDrawer(
    private val dir: String,
    private val files: OfflineFiles,
    private val open: (String) -> PmTiles = { PmTiles.openPath(it) },
) {
    private companion object {
        const val TAG = "OfflineMap"
        /** How many zooms a tile may be drawn from above it: past this the
         *  scaled-up geometry is too coarse to be worth showing. */
        const val MAX_UP = 7
    }

    private class Open(val stamp: Long, val pm: PmTiles?, val id: Int)

    private val lock = SynchronizedObject()
    private val squares = HashMap<String, Open>()
    private var opened = 0
    private val decodedLock = SynchronizedObject()
    private val decoded = LruMap<String, VectorTile>(48)

    /**
     * The tile [size] pixels wide, drawn on the canvas [surface] makes and
     * given back by [finish], or null when no square has it.
     */
    fun <C : TileCanvas, T> render(z: Int, x: Int, y: Int, size: Int, surface: (Int) -> C, finish: (C) -> T): T? {
        val (pm, id) = square(z, x, y) ?: return null
        val layers = HashMap<String, Source>()
        for (name in OfflineMapStyle.LAYERS) {
            val src = source(pm, id, name, z, x, y, size) ?: continue
            layers[name] = src
        }
        if (layers.isEmpty()) return null
        val c = surface(size)
        draw(c, layers, z, size)
        return finish(c)
    }

    /** A name for a spot from the downloaded map, or null without one. */
    fun addressAt(lat: Double, lng: Double): String? {
        val pm = openSquare(RoutingTiles.nameFor(lat, lng))?.pm ?: return null
        return runCatching { OfflineAddress.at(pm, lat, lng) }.getOrNull()
    }

    /** Forgets open squares, after one is downloaded or deleted. */
    fun reset() = synchronized(lock) {
        squares.values.forEach { runCatching { it.pm?.close() } }
        squares.clear()
        synchronized(decodedLock) { decoded.clear() }
    }

    /** One layer from the tile itself or the ancestor it is stored at, with
     *  the scale and shift that map its units onto this tile's pixels. */
    private class Source(val layer: VectorTile.Layer, val scale: Float, val dx: Float, val dy: Float)

    private fun source(pm: PmTiles, id: Int, name: String, z: Int, x: Int, y: Int, size: Int): Source? {
        // Outside towns a square stops before its deepest zooms: draw from
        // the nearest ancestor it has.
        var sz = min(z, min(pm.maxZoom, OfflineMapStyle.LAST_ZOOM[name] ?: z))
        var vt: VectorTile? = null
        while (sz >= 0 && z - sz <= MAX_UP) {
            vt = vector(pm, id, sz, x shr (z - sz), y shr (z - sz))
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

    private fun vector(pm: PmTiles, id: Int, z: Int, x: Int, y: Int): VectorTile? {
        val key = "$id/$z/$x/$y"
        synchronized(decodedLock) { decoded[key]?.let { return it } }
        val bytes = runCatching { pm.tile(z, x, y) }.onFailure { Log.w(TAG, "read $z/$x/$y: $it") }.getOrNull() ?: return null
        val vt = runCatching { VectorTile.decode(bytes) }.onFailure { Log.w(TAG, "decode $z/$x/$y: $it") }.getOrNull() ?: return null
        synchronized(decodedLock) { decoded[key] = vt }
        return vt
    }

    private fun square(z: Int, x: Int, y: Int): Pair<PmTiles, Int>? {
        // A tile across a square's edge is in both squares; a wide one, in
        // any square under it.
        val names = TileMath.squaresUnder(z, x, y)
        for (name in if (z <= 8) names else names.take(1)) {
            val o = openSquare(name) ?: continue
            val pm = o.pm ?: continue
            return pm to o.id
        }
        return null
    }

    private fun openSquare(name: String): Open? = synchronized(lock) {
        val f = joinPath(dir, "$name.pmtiles")
        val stamp = if (files.isFile(f)) files.lastModified(f) xor files.length(f) else 0L
        val have = squares[name]
        if (have != null && have.stamp == stamp) return have
        have?.pm?.let { runCatching { it.close() } }
        val pm = if (stamp == 0L) null else runCatching { open(f) }.onFailure { Log.w(TAG, "$name: $it") }.getOrNull()
        val o = Open(stamp, pm, ++opened)
        squares[name] = o
        o
    }

    private fun path(c: TileCanvas, f: VectorTile.Feature, s: Source, close: Boolean): TilePath {
        val p = c.newPath()
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
        p.evenOdd()
        return p
    }

    private class Road(val feature: VectorTile.Feature, val look: OfflineMapStyle.Line, val path: TilePath)

    private fun draw(c: TileCanvas, layers: Map<String, Source>, z: Int, size: Int) {
        val k = size / 256f
        c.drawColor(OfflineMapStyle.WATER)

        layers["earth"]?.let { s ->
            for (f in s.layer.features) if (f.type == VectorTile.POLYGON) c.fill(path(c, f, s, true), OfflineMapStyle.LAND)
        }
        layers["landuse"]?.let { s ->
            for (f in s.layer.features) {
                if (f.type != VectorTile.POLYGON) continue
                val color = OfflineMapStyle.landuse(f.str("kind")) ?: continue
                c.fill(path(c, f, s, true), color)
            }
        }
        layers["water"]?.let { s ->
            for (f in s.layer.features) {
                when (f.type) {
                    VectorTile.POLYGON -> c.fill(path(c, f, s, true), OfflineMapStyle.WATER)
                    VectorTile.LINE -> {
                        val w = (OfflineMapStyle.waterLine(f.str("kind"), z) ?: continue) * k
                        c.stroke(path(c, f, s, false), OfflineMapStyle.WATER, w, 0f)
                    }
                }
            }
        }
        if (z >= 14) layers["buildings"]?.let { s ->
            for (f in s.layer.features) {
                if (f.type != VectorTile.POLYGON) continue
                val p = path(c, f, s, true)
                c.fill(p, OfflineMapStyle.BUILDING)
                if (z >= 16) c.stroke(p, OfflineMapStyle.BUILDING_EDGE, 0.6f * k, 0f)
            }
        }
        val roads = layers["roads"]?.let { s ->
            s.layer.features.filter { it.type == VectorTile.LINE }.mapNotNull { f ->
                OfflineMapStyle.road(f.str("kind"), f.str("kind_detail"), z)?.let { Road(f, it, path(c, f, s, false)) }
            }.sortedBy { it.look.order }
        }.orEmpty()
        for (r in roads) {
            val casing = r.look.casing ?: continue
            if (r.look.width * k < 1.5f) continue
            c.stroke(r.path, casing, r.look.width * k + 1.5f * k, 0f)
        }
        for (r in roads) {
            c.stroke(r.path, r.look.color, r.look.width * k, if (r.look.dash) 2.5f * k else 0f)
        }

        labels(c, layers, roads, z, k)
    }

    private fun labels(c: TileCanvas, layers: Map<String, Source>, roads: List<Road>, z: Int, k: Float) {
        val taken = ArrayList<FloatArray>()
        // Android drew these with one pair of paints changed as it went: the
        // road names leave them measuring from the left with mitred halos,
        // and the house numbers after them keep that. Kept as it always drew.
        var centered = true
        var roundHalo = true
        fun place(label: String, x: Float, y: Float, sizePx: Float, bold: Boolean, color: Int): Boolean {
            val style = TileText(sizePx, bold, color, OfflineMapStyle.HALO, sizePx / 5f, centered, roundHalo)
            val w = c.measureText(label, style)
            val r = floatArrayOf(x - w / 2 - 2, y - sizePx, x + w / 2 + 2, y + sizePx * 0.3f)
            if (r[0] < 0 || r[1] < 0 || r[2] > c.width || r[3] > c.height) return false
            if (taken.any { it[0] < r[2] && r[0] < it[2] && it[1] < r[3] && r[1] < it[3] }) return false
            taken += r
            c.drawText(label, x, y, style)
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
            centered = false
            roundHalo = false
            val style = TileText(10.5f * k, false, OfflineMapStyle.TEXT, OfflineMapStyle.HALO, 2.5f * k, centered = false, roundHalo = false)
            val done = HashSet<String>()
            val src = layers["roads"]
            for (r in roads.asReversed()) {
                val name = r.feature.str("name") ?: continue
                if (r.look.order < 3 || !done.add(name)) continue
                val ring = r.feature.rings.maxByOrNull { it.size } ?: continue
                val forward = ring[0] <= ring[ring.size - 2]
                val p = c.newPath()
                src!!
                var i = if (forward) 0 else ring.size - 2
                p.moveTo(ring[i] * src.scale + src.dx, ring[i + 1] * src.scale + src.dy)
                while (true) {
                    i += if (forward) 2 else -2
                    if (i < 0 || i >= ring.size) break
                    p.lineTo(ring[i] * src.scale + src.dx, ring[i + 1] * src.scale + src.dy)
                }
                val len = c.length(p)
                val w = c.measureText(name, style)
                if (len < w + 24 * k) continue
                val b = c.bounds(p)
                if (b[2] < 0 || b[3] < 0 || b[0] > c.width || b[1] > c.height) continue
                val off = (len - w) / 2
                c.drawTextOnPath(name, p, off, style.size * 0.35f, style)
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
