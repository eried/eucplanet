package com.eried.eucplanet.util

import com.eried.eucplanet.util.ByteUtils.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteUtilsTest {
    @Test fun uint16LE() {
        assertEquals(0x3412, ByteUtils.getUint16LE(byteArrayOf(0x12, 0x34), 0))
    }

    @Test fun int16BE_negative() {
        // 0xFFFE big-endian = -2
        assertEquals(-2, ByteUtils.getInt16BE(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), 0))
    }

    @Test fun wordSwappedUint32_veteran() {
        // bytes b0 b1 b2 b3 -> (b2<<24)|(b3<<16)|(b0<<8)|b1
        val v = ByteUtils.getWordSwappedUint32(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), 0)
        assertEquals(0x0C0D_0A0BL, v)
    }

    @Test fun hexStringIsLowercaseSpaceSeparated() {
        assertEquals("00 0f a0 ff", byteArrayOf(0x00, 0x0F, 0xA0.toByte(), 0xFF.toByte()).toHexString())
    }

    @Test fun parseTemperatureOffset() {
        assertEquals(0f, ByteUtils.parseTemperature(176.toByte()))
    }
}
