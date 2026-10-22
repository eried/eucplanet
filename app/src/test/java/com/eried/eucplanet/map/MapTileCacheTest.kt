package com.eried.eucplanet.map

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapTileCacheTest {
    @Test
    fun readLimitedBytesRejectsPayloadsOverLimit() {
        val payload = ByteArray(9) { it.toByte() }

        assertNull(ByteArrayInputStream(payload).readLimitedBytes(limit = 8))
    }

    @Test
    fun readLimitedBytesPreservesPayloadWithinLimit() {
        val payload = ByteArray(8) { it.toByte() }

        assertArrayEquals(
            payload,
            ByteArrayInputStream(payload).readLimitedBytes(limit = 8),
        )
    }
}
