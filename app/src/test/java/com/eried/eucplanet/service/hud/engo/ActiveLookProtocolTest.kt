package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact tests for [ActiveLookProtocol] against the published ActiveLook
 * frame + opcode spec. No hardware needed - this is the correctness core.
 */
class ActiveLookProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test
    fun clear_matchesSpecExample() {
        // Spec: FF 01 00 05 AA (whole-frame length = 5, no data).
        assertEquals("ff 01 00 05 aa", hex(ActiveLookProtocol.clear()))
    }

    @Test
    fun everyFrameHasStartAndFooterAndSelfLength() {
        for (f in listOf(
            ActiveLookProtocol.clear(),
            ActiveLookProtocol.luma(8),
            ActiveLookProtocol.rectf(0, 0, 10, 10),
            ActiveLookProtocol.txt(1, 2, 0, 1, 15, "hi"),
        )) {
            assertEquals("start byte", 0xFF.toByte(), f[0])
            assertEquals("footer byte", 0xAA.toByte(), f.last())
            // Short frames encode their own total length at index 3.
            assertEquals("self length", f.size, f[3].toInt() and 0xFF)
            assertEquals("format short", 0x00, f[2].toInt() and 0xFF)
        }
    }

    @Test
    fun luma_clampsTo0_15() {
        assertEquals("ff 10 00 06 0f aa", hex(ActiveLookProtocol.luma(99)))
        assertEquals("ff 10 00 06 00 aa", hex(ActiveLookProtocol.luma(-3)))
    }

    @Test
    fun holdFlush_holdThenFlush() {
        assertEquals("ff 39 00 06 00 aa", hex(ActiveLookProtocol.holdFlush(ActiveLookProtocol.HOLD)))
        assertEquals("ff 39 00 06 01 aa", hex(ActiveLookProtocol.holdFlush(ActiveLookProtocol.FLUSH)))
    }

    @Test
    fun rectf_bigEndianCoords() {
        // x0=0 y0=0 x1=303(0x012F) y1=255(0x00FF); data 8B, total 13 (0x0D).
        assertEquals(
            "ff 34 00 0d 00 00 00 00 01 2f 00 ff aa",
            hex(ActiveLookProtocol.rectf(0, 0, 303, 255)),
        )
    }

    @Test
    fun line_negativeCoordsAreTwosComplementS16() {
        // x0=-10 -> 0xFFF6.
        val f = ActiveLookProtocol.line(-10, 0, 0, 0)
        assertEquals("ff 32 00 0d ff f6 00 00 00 00 00 00 aa", hex(f))
    }

    @Test
    fun txt_layoutAndNulTerminator() {
        // x=10(000A) y=20(0014) rot=0 font=1 grey=15(0F) "42"=34 32 then NUL 00.
        assertEquals(
            "ff 37 00 0f 00 0a 00 14 00 01 0f 34 32 00 aa",
            hex(ActiveLookProtocol.txt(10, 20, 0, 1, 15, "42")),
        )
    }

    @Test
    fun txtColor_usesColorOpcode() {
        val f = ActiveLookProtocol.txtColor(0, 0, 0, 1, 0xE0, "R")
        assertEquals(0x3E, f[1].toInt() and 0xFF)
        // ...font, color(0xE0), 'R'(0x52), NUL.
        assertTrue(hex(f).contains("e0 52 00"))
    }

    @Test
    fun grayscaleAndColor_clampAndOpcode() {
        assertEquals("ff 30 00 06 0f aa", hex(ActiveLookProtocol.grayscale(200)))
        assertEquals("ff 3d 00 06 e0 aa", hex(ActiveLookProtocol.color(0xE0)))
    }

    @Test
    fun arc_paramOrderAndSize() {
        // x=152 y=128 r=40 a0=90 a1=270 thick=6 -> data 10B, total 15 (0x0F).
        assertEquals(
            "ff 3c 00 0f 00 98 00 80 28 00 5a 01 0e 06 aa",
            hex(ActiveLookProtocol.arc(152, 128, 40, 90, 270, 6)),
        )
    }

    @Test
    fun longFrame_switchesTo2ByteLength() {
        // A text > ~250 chars pushes the frame past 255 bytes -> format 0x20,
        // 2-byte Big-Endian length, header is 6 bytes not 5.
        val f = ActiveLookProtocol.txt(0, 0, 0, 1, 15, "a".repeat(300))
        assertEquals("start", 0xFF.toByte(), f[0])
        assertEquals("format 2-byte len", 0x20, f[2].toInt() and 0xFF)
        val declared = ((f[3].toInt() and 0xFF) shl 8) or (f[4].toInt() and 0xFF)
        assertEquals("declared == actual", f.size, declared)
        assertEquals("footer", 0xAA.toByte(), f.last())
    }
}
