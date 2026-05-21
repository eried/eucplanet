package com.eried.eucplanet.ui.studio.recording

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal streaming APNG (animated PNG) encoder, used to export a transparent
 * Overlay Studio replay clip. MP4/H.264 has no alpha channel, so the replay
 * video is written as an animated PNG instead.
 *
 * Frames are added one at a time and written immediately, so only a single
 * bitmap is ever held. Every frame is full-size with blend op SOURCE and
 * dispose op NONE, so frames are independent (no inter-frame deltas).
 *
 * Call [addFrame] exactly [frameCount] times in order, then [finish].
 */
class StudioApngEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val frameCount: Int,
    private val delayMs: Int
) {
    private var seq = 0
    private var started = false

    private fun beginIfNeeded() {
        if (started) return
        started = true
        out.write(SIGNATURE)
        chunk("IHDR", ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(8)                 // bit depth
            write(6)                 // colour type 6 = RGBA
            write(0); write(0); write(0)  // compression / filter / interlace
        }.toByteArray())
        chunk("acTL", ByteArrayOutputStream().apply {
            writeInt(frameCount.coerceAtLeast(1))
            writeInt(0)              // play count: 0 = loop forever
        }.toByteArray())
    }

    /** Append the next frame. The bitmap is scaled/copied to the target size. */
    fun addFrame(source: Bitmap) {
        beginIfNeeded()
        val bm = toSoftwareRgba(source)
        chunk("fcTL", ByteArrayOutputStream().apply {
            writeInt(seq++)
            writeInt(width); writeInt(height)
            writeInt(0); writeInt(0)            // x / y offset
            writeShort(delayMs); writeShort(1000)  // delay_num / delay_den
            write(0)                            // dispose op: NONE
            write(0)                            // blend op: SOURCE
        }.toByteArray())
        val data = deflate(rawRgba(bm))
        if (seq == 1) {
            // First frame is the default image — a plain IDAT chunk.
            chunk("IDAT", data)
        } else {
            chunk("fdAT", ByteArrayOutputStream().apply {
                writeInt(seq++)
                write(data)
            }.toByteArray())
        }
        if (bm !== source) bm.recycle()
    }

    fun finish() {
        beginIfNeeded()
        chunk("IEND", ByteArray(0))
        out.flush()
    }

    // --- internals ----------------------------------------------------------

    private fun toSoftwareRgba(b: Bitmap): Bitmap {
        var bm = b
        if (bm.width != width || bm.height != height) {
            bm = Bitmap.createScaledBitmap(bm, width, height, true)
        }
        if (bm.config != Bitmap.Config.ARGB_8888) {
            val copy = bm.copy(Bitmap.Config.ARGB_8888, false)
            if (bm !== b) bm.recycle()
            bm = copy
        }
        return bm
    }

    /** Bitmap -> RGBA scanlines, each prefixed with filter byte 0 (none). */
    private fun rawRgba(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val raw = ByteArray(height * (1 + width * 4))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            var i = y * width
            for (x in 0 until width) {
                val c = pixels[i++]
                raw[p++] = ((c shr 16) and 0xFF).toByte()  // R
                raw[p++] = ((c shr 8) and 0xFF).toByte()   // G
                raw[p++] = (c and 0xFF).toByte()            // B
                raw[p++] = ((c ushr 24) and 0xFF).toByte()  // A
            }
        }
        return raw
    }

    private fun deflate(input: ByteArray): ByteArray {
        // BEST_SPEED — the replay render does hundreds of frames, and the
        // overlays are mostly flat colour so the size cost is small.
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(input)
        deflater.finish()
        val buf = ByteArray(64 * 1024)
        val bos = ByteArrayOutputStream()
        while (!deflater.finished()) {
            bos.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(out, data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        writeInt(out, crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun writeInt(o: OutputStream, v: Int) {
        o.write((v ushr 24) and 0xFF); o.write((v ushr 16) and 0xFF)
        o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }

    companion object {
        private val SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
