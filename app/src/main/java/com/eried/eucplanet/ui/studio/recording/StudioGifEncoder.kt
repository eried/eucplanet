package com.eried.eucplanet.ui.studio.recording

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Minimal streaming animated-GIF encoder for the Overlay Studio replay clip.
 *
 * Unlike APNG, an animated GIF opens in every gallery, browser and chat app.
 * The trade-offs are GIF's own: 256 colours per frame (here via a count-
 * weighted median-cut palette) and 1-bit transparency: a pixel is either fully
 * opaque or fully clear. Each frame is quantised and written immediately, so
 * only one frame is ever held.
 *
 * Call [addFrame] in order, then [finish].
 */
class StudioGifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val delayMs: Int
) {
    private var started = false
    private val argb = IntArray(width * height)
    private val indices = ByteArray(width * height)

    // Sub-block output buffer (GIF data is chunked into <=255-byte blocks).
    private val blockBuf = ByteArray(255)
    private var blockLen = 0

    // --- Frames ------------------------------------------------------------

    /** Quantise [bitmap] and append it as one GIF frame. */
    fun addFrame(bitmap: Bitmap) {
        beginIfNeeded()
        // GraphicsLayer.toImageBitmap() hands back a HARDWARE bitmap; getPixels()
        // only works on a software bitmap, so copy when needed.
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        } else {
            bitmap
        }
        val src = if (software.width == width && software.height == height) {
            software
        } else {
            Bitmap.createScaledBitmap(software, width, height, true)
        }
        src.getPixels(argb, 0, width, 0, 0, width, height)
        if (src !== software) src.recycle()
        if (software !== bitmap) software.recycle()

        // Distinct opaque colours with pixel counts -> median-cut palette.
        val counts = HashMap<Int, Int>()
        for (p in argb) {
            if ((p ushr 24) >= ALPHA_CUTOFF) {
                val rgb = p and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        val palette = quantize(counts, MAX_COLORS)
        val transIndex = palette.size // one slot past the colours is "transparent"

        // Nearest-colour matching is per-pixel independent; fan the bands out
        // across cores so a full-resolution frame quantises in a fraction of
        // the time. Each band keeps its own colour cache, writes a disjoint
        // slice of `indices`, and nearestIndex() is pure; so no shared state.
        val tIndex = transIndex.toByte()
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val band = (argb.size + cores - 1) / cores
        (0 until cores).map { c ->
            Thread {
                val cache = HashMap<Int, Int>()
                val start = c * band
                val end = minOf(start + band, argb.size)
                for (i in start until end) {
                    val p = argb[i]
                    indices[i] = if ((p ushr 24) < ALPHA_CUTOFF) {
                        tIndex
                    } else {
                        val rgb = p and 0xFFFFFF
                        (cache[rgb]
                            ?: nearestIndex(rgb, palette).also { cache[rgb] = it }).toByte()
                    }
                }
            }.apply { start() }
        }.forEach { it.join() }

        // Colour table size must be a power of two covering every index used.
        var bits = 1
        while ((1 shl bits) < transIndex + 1) bits++
        val tableSize = 1 shl bits

        // Graphic control extension: delay + transparent index, dispose to bg.
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x09) // disposal method 2 (restore to background) | transparent flag
        writeShort((delayMs / 10).coerceAtLeast(2)) // delay, hundredths of a second
        out.write(transIndex and 0xFF)
        out.write(0)
        // Image descriptor.
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0x80 or (bits - 1)) // local colour table present, of `bits` size
        // Local colour table.
        for (i in 0 until tableSize) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c ushr 16) and 0xFF)
            out.write((c ushr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        // LZW-compressed pixel indices.
        val litWidth = maxOf(2, bits)
        out.write(litWidth)
        lzwEncode(indices, litWidth)
        out.write(0) // image-data block terminator
    }

    /** Write the GIF trailer and flush. */
    fun finish() {
        beginIfNeeded()
        out.write(0x3B)
        out.flush()
    }

    // --- Header ------------------------------------------------------------

    private fun beginIfNeeded() {
        if (started) return
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor: no global colour table (each frame has one).
        writeShort(width)
        writeShort(height)
        out.write(0x70) // 8-bit colour resolution, no global table
        out.write(0)    // background colour index
        out.write(0)    // pixel aspect ratio
        // NETSCAPE2.0 application extension: loop forever.
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); writeShort(0); out.write(0)
    }

    // --- LZW (GIF variant) -------------------------------------------------

    private fun lzwEncode(pixels: ByteArray, litWidth: Int) {
        val clearCode = 1 shl litWidth
        val eoiCode = clearCode + 1
        val dict = HashMap<Int, Int>()
        var codeWidth = litWidth + 1
        var hi = eoiCode          // highest code assigned so far
        var overflow = 1 shl codeWidth

        var bitBuffer = 0
        var bitCount = 0
        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeWidth
            while (bitCount >= 8) {
                blockByte(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        emit(clearCode)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) {
                prefix = found
            } else {
                emit(prefix)
                if (hi < 4095) {
                    hi++
                    dict[key] = hi
                    if (hi == overflow) {
                        codeWidth++
                        overflow = overflow shl 1
                    }
                } else {
                    // Dictionary full, restart it.
                    emit(clearCode)
                    dict.clear()
                    codeWidth = litWidth + 1
                    overflow = 1 shl codeWidth
                    hi = eoiCode
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(eoiCode)
        if (bitCount > 0) blockByte(bitBuffer and 0xFF)
        flushBlock()
    }

    // --- Colour quantisation ----------------------------------------------

    /** Count-weighted median-cut down to at most [maxColors] colours. */
    private fun quantize(counts: Map<Int, Int>, maxColors: Int): IntArray {
        val colors = counts.keys.toIntArray()
        if (colors.size <= maxColors) return colors
        val arr = colors.copyOf()
        var boxes = mutableListOf(IntRange(0, arr.size - 1))
        while (boxes.size < maxColors) {
            var target = -1
            var targetExtent = -1
            var targetShift = 0
            for (bi in boxes.indices) {
                val b = boxes[bi]
                if (b.last <= b.first) continue
                var rmn = 255; var rmx = 0; var gmn = 255
                var gmx = 0; var bmn = 255; var bmx = 0
                for (i in b) {
                    val c = arr[i]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val bl = c and 0xFF
                    if (r < rmn) rmn = r; if (r > rmx) rmx = r
                    if (g < gmn) gmn = g; if (g > gmx) gmx = g
                    if (bl < bmn) bmn = bl; if (bl > bmx) bmx = bl
                }
                val rr = rmx - rmn; val gr = gmx - gmn; val br = bmx - bmn
                val ext = maxOf(rr, gr, br)
                if (ext > targetExtent) {
                    targetExtent = ext
                    target = bi
                    targetShift = if (rr >= gr && rr >= br) 16 else if (gr >= br) 8 else 0
                }
            }
            if (target < 0) break // every box is a single colour
            val b = boxes[target]
            val sorted = arr.copyOfRange(b.first, b.last + 1)
                .sortedBy { (it ushr targetShift) and 0xFF }
            for (i in sorted.indices) arr[b.first + i] = sorted[i]
            val mid = b.first + (b.last - b.first) / 2
            boxes[target] = IntRange(b.first, mid)
            boxes.add(IntRange(mid + 1, b.last))
        }
        return IntArray(boxes.size) { bi ->
            val b = boxes[bi]
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (i in b) {
                val c = arr[i]
                val w = (counts[c] ?: 1).toLong()
                r += ((c ushr 16) and 0xFF) * w
                g += ((c ushr 8) and 0xFF) * w
                bl += (c and 0xFF) * w
                n += w
            }
            if (n == 0L) n = 1
            (((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt())
        }
    }

    private fun nearestIndex(rgb: Int, palette: IntArray): Int {
        if (palette.isEmpty()) return 0
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = r - ((c ushr 16) and 0xFF)
            val dg = g - ((c ushr 8) and 0xFF)
            val db = b - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                best = i
                if (d == 0) break
            }
        }
        return best
    }

    // --- Byte output -------------------------------------------------------

    private fun blockByte(b: Int) {
        blockBuf[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(blockBuf, 0, blockLen)
            blockLen = 0
        }
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private companion object {
        /** A pixel at or above this alpha is opaque; below it, fully transparent. */
        const val ALPHA_CUTOFF = 128

        /** Palette colours per frame; one more slot is kept for transparency. */
        const val MAX_COLORS = 255
    }
}
