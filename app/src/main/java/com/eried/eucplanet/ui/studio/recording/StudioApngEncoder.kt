package com.eried.eucplanet.ui.studio.recording

import android.graphics.Bitmap
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal streaming animated-PNG (APNG) encoder for the Overlay Studio replay
 * clip.
 *
 * Unlike GIF, APNG keeps the studio's full 8-bit RGBA alpha — soft shadows and
 * anti-aliased overlay edges survive the export instead of being crushed to a
 * 1-bit mask. The trade-off is reach: APNG plays in modern browsers and the
 * stock Android gallery, but not every chat app animates it.
 *
 * Each frame is deflate-compressed and written immediately, so only one frame
 * is ever held in memory. Layout follows the APNG spec:
 *   PNG signature
 *   IHDR
 *   acTL                                  (frame + loop count)
 *   per frame: fcTL, then IDAT (frame 0) / fdAT (the rest)
 *   IEND
 * Every chunk is length-prefixed and CRC-32'd over its type + data; fdAT
 * carries a 4-byte sequence number ahead of its compressed pixels.
 *
 * Call [addFrame] in order, then [finish].
 */
class StudioApngEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val delayMs: Int,
    private val frameCount: Int
) {
    private var started = false
    /** Running APNG sequence number — shared by fcTL and fdAT chunks. */
    private var sequence = 0
    private val crc = CRC32()
    private val argb = IntArray(width * height)

    // --- Frames ------------------------------------------------------------

    /** Append [bitmap] as one APNG frame (color-typed 6: RGBA, 8 bit). */
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

        // fcTL — frame control. Must precede every frame's pixel data.
        writeFcTl()

        // Raw image: each scanline is a 1-byte filter (0 = none) then RGBA.
        val raw = ByteArray(height * (1 + width * 4))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter: none
            val rowBase = y * width
            for (x in 0 until width) {
                val c = argb[rowBase + x]
                raw[p++] = ((c ushr 16) and 0xFF).toByte() // R
                raw[p++] = ((c ushr 8) and 0xFF).toByte()  // G
                raw[p++] = (c and 0xFF).toByte()           // B
                raw[p++] = ((c ushr 24) and 0xFF).toByte() // A
            }
        }
        val compressed = deflate(raw)

        if (isFirstFrameData()) {
            // Frame 0's pixels are the default image — a plain IDAT chunk.
            writeChunk("IDAT", compressed)
        } else {
            // Later frames go in fdAT: a 4-byte sequence number, then pixels.
            val fdat = ByteArray(4 + compressed.size)
            writeUInt32(fdat, 0, sequence++)
            System.arraycopy(compressed, 0, fdat, 4, compressed.size)
            writeChunk("fdAT", fdat)
        }
        framesWritten++
    }

    /** Write the IEND trailer and flush. */
    fun finish() {
        beginIfNeeded()
        writeChunk("IEND", ByteArray(0))
        out.flush()
    }

    // --- Header ------------------------------------------------------------

    private var framesWritten = 0

    private fun beginIfNeeded() {
        if (started) return
        started = true
        // PNG signature.
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        // IHDR — 8-bit, color type 6 (RGBA), no interlace.
        val ihdr = ByteArray(13)
        writeUInt32(ihdr, 0, width)
        writeUInt32(ihdr, 4, height)
        ihdr[8] = 8  // bit depth
        ihdr[9] = 6  // color type: RGBA
        ihdr[10] = 0 // compression: deflate
        ihdr[11] = 0 // filter method: standard
        ihdr[12] = 0 // interlace: none
        writeChunk("IHDR", ihdr)
        // acTL — animation control: frame count + loop forever (0).
        val actl = ByteArray(8)
        writeUInt32(actl, 0, frameCount.coerceAtLeast(1))
        writeUInt32(actl, 4, 0)
        writeChunk("acTL", actl)
    }

    /** True when the next frame's pixels are the default image (IDAT). */
    private fun isFirstFrameData(): Boolean = framesWritten == 0

    // --- fcTL --------------------------------------------------------------

    private fun writeFcTl() {
        // Express the delay as a fraction: numerator / denominator seconds.
        val delayNum = delayMs.coerceAtLeast(10)
        val data = ByteArray(26)
        writeUInt32(data, 0, sequence++)        // sequence number
        writeUInt32(data, 4, width)             // frame width
        writeUInt32(data, 8, height)            // frame height
        writeUInt32(data, 12, 0)                // x offset
        writeUInt32(data, 16, 0)                // y offset
        writeUInt16(data, 20, delayNum)         // delay numerator
        writeUInt16(data, 22, 1000)             // delay denominator (ms)
        data[24] = 1 // dispose op: APNG_DISPOSE_OP_BACKGROUND (clear to transparent)
        data[25] = 0 // blend op: APNG_BLEND_OP_SOURCE (overwrite, keep alpha)
        writeChunk("fcTL", data)
    }

    // --- Deflate -----------------------------------------------------------

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(64 * 1024)
        val sink = ArrayList<ByteArray>()
        var total = 0
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) {
                sink.add(buf.copyOf(n))
                total += n
            }
        }
        deflater.end()
        val result = ByteArray(total)
        var off = 0
        for (chunk in sink) {
            System.arraycopy(chunk, 0, result, off, chunk.size)
            off += chunk.size
        }
        return result
    }

    // --- Chunk output ------------------------------------------------------

    /** Write one PNG chunk: length, type, data, CRC-32 over type + data. */
    private fun writeChunk(type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeUInt32(len, 0, data.size)
        out.write(len)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        crc.reset()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        writeUInt32(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun writeUInt32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUInt16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }
}
