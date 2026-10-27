package com.eried.eucplanet.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Pins [CharsetCodec] (the iOS charset rules) to the JVM's US-ASCII and ISO-8859-1. */
class CharsetCodecTest {
    @Test
    fun decodeMatchesJvm() {
        val r = Random(7)
        repeat(2_000) {
            val bytes = r.nextBytes(r.nextInt(0, 40))
            val off = if (bytes.isEmpty()) 0 else r.nextInt(0, bytes.size)
            val len = bytes.size - off
            assertEquals(String(bytes, off, len, kotlin.text.Charsets.US_ASCII), CharsetCodec.decodeAscii(bytes, off, len))
            assertEquals(String(bytes, off, len, kotlin.text.Charsets.ISO_8859_1), CharsetCodec.decodeLatin1(bytes, off, len))
        }
    }

    @Test
    fun encodeMatchesJvm() {
        for (s in listOf("", "SetLightON", "GW2-MASTER-1.42", "café", "ÿĀ", "a😀b", "\uD83Dx", "x\uDE00")) {
            assertArrayEquals(s, s.toByteArray(kotlin.text.Charsets.US_ASCII), CharsetCodec.encodeSingleByte(s, 0x7F))
            assertArrayEquals(s, s.toByteArray(kotlin.text.Charsets.ISO_8859_1), CharsetCodec.encodeSingleByte(s, 0xFF))
        }
    }

    @Test
    fun byteBufferMatchesJvmStream() {
        val r = Random(3)
        val jvm = java.io.ByteArrayOutputStream()
        val ours = ByteBuffer(4)
        repeat(500) {
            when (r.nextInt(4)) {
                0 -> r.nextInt(-300, 300).let { jvm.write(it); ours.write(it) }
                1 -> r.nextBytes(r.nextInt(0, 50)).let { b ->
                    val off = if (b.isEmpty()) 0 else r.nextInt(0, b.size)
                    jvm.write(b, off, b.size - off); ours.write(b, off, b.size - off)
                }
                2 -> if (r.nextInt(10) == 0) { jvm.reset(); ours.reset() }
                else -> assertEquals(jvm.size(), ours.size())
            }
            assertArrayEquals(jvm.toByteArray(), ours.toByteArray())
        }
    }
}
