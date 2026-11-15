package com.eried.eucplanet.ui.studio.recording

import com.eried.eucplanet.platform.ByteArrayOutputStream
import com.eried.eucplanet.platform.inflateRaw
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay clip encoders, now that they take ARGB pixels instead of an
 * Android Bitmap: the files they write are well formed on every phone, and an
 * APNG frame unpacks back to exactly the pixels it was given.
 */
class StudioClipEncoderTest {

    private class Sink : StudioByteSink {
        val out = ByteArrayOutputStream()
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = Unit
    }

    private val w = 6
    private val h = 4

    /** A small frame: a colour ramp with one clear row, shifted per frame. */
    private fun frame(i: Int) = IntArray(w * h) { p ->
        val x = p % w
        val y = p / w
        if (y == 0) 0x00000000 else (0xFF shl 24) or (((x * 40 + i * 10) and 0xFF) shl 16) or ((y * 60) shl 8) or 0x30
    }

    @Test fun `a GIF clip has its header and trailer and one image per frame`() {
        val sink = Sink()
        val gif = StudioGifEncoder(sink, w, h, 100)
        repeat(3) { gif.addFrame(frame(it)) }
        gif.finish()
        val bytes = sink.out.toByteArray()
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).decodeToString())
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        // Every frame opens with a graphic control extension (21 F9 04).
        var gce = 0
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 4.toByte()) gce++
        }
        assertEquals(3, gce)
    }

    @Test fun `an APNG frame unpacks back to the pixels it was given`() {
        val sink = Sink()
        val apng = StudioApngEncoder(sink, w, h, 100, 2)
        apng.addFrame(frame(0))
        apng.addFrame(frame(1))
        apng.finish()
        val bytes = sink.out.toByteArray()
        assertContentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
        val chunks = chunks(bytes)
        assertEquals(listOf("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"), chunks.map { it.first })
        // IDAT is a zlib stream: a 2-byte header, raw deflate, a 4-byte checksum.
        val idat = chunks.first { it.first == "IDAT" }.second
        val raw = inflateRaw(idat.copyOfRange(2, idat.size - 4))
        val expected = frame(0)
        assertEquals(h * (1 + w * 4), raw.size)
        for (y in 0 until h) {
            assertEquals(0, raw[y * (1 + w * 4)].toInt(), "row $y filter")
            for (x in 0 until w) {
                val c = expected[y * w + x]
                val o = y * (1 + w * 4) + 1 + x * 4
                val got = ((raw[o + 3].toInt() and 0xFF) shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or
                    ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
                assertEquals(c, got, "pixel $x,$y")
            }
        }
        assertTrue(chunks.first { it.first == "fdAT" }.second.size > 4)
    }

    /** The (type, data) of every PNG chunk after the signature. */
    private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var p = 8
        while (p < bytes.size) {
            val len = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
            val type = bytes.copyOfRange(p + 4, p + 8).decodeToString()
            out += type to bytes.copyOfRange(p + 8, p + 8 + len)
            p += 12 + len
        }
        return out
    }
}
