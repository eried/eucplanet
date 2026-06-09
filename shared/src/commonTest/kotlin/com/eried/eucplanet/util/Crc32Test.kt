package com.eried.eucplanet.util

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test fun standardCheckVector() {
        // CRC-32/ISO-HDLC check value for ASCII "123456789" is 0xCBF43926.
        val data = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926L, Crc32.compute(data))
    }

    @Test fun emptyIsZero() {
        assertEquals(0L, Crc32.compute(ByteArray(0)))
    }

    @Test fun respectsOffsetAndLength() {
        // CRC over the middle "123456789" of a padded buffer must match the vector.
        val padded = byteArrayOf(0x00, 0x00) + "123456789".encodeToByteArray() + byteArrayOf(0x00)
        assertEquals(0xCBF43926L, Crc32.compute(padded, offset = 2, length = 9))
    }
}
