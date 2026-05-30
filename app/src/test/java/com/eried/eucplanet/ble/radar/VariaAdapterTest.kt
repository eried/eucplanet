package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.data.model.RadarVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [VariaAdapter.decode]. These run on the JVM, no Android
 * runtime is needed because the adapter is pure Kotlin.
 *
 * The Varia wire format and fragmentation rules are documented in the
 * KDoc on [VariaAdapter]; these tests pin the behaviour described there.
 */
class VariaAdapterTest {

    private lateinit var adapter: VariaAdapter

    @Before
    fun setUp() {
        adapter = VariaAdapter()
    }

    private fun bytes(vararg v: Int): ByteArray =
        ByteArray(v.size) { v[it].toByte() }

    @Test
    fun vendor_is_varia() {
        assertEquals(RadarVendor.VARIA, adapter.vendor)
    }

    @Test
    fun matches_namePrefixes_caseInsensitive() {
        assertTrue(adapter.matches("RTL515"))
        assertTrue(adapter.matches("rtl516"))
        assertTrue(adapter.matches("RVR315"))
        assertTrue(adapter.matches("RCT715"))
        assertTrue(adapter.matches("eRTL615"))
        assertTrue(adapter.matches("Varia"))
        assertTrue(adapter.matches("  varia  "))
    }

    @Test
    fun matches_rejectsUnknownPrefix() {
        assertEquals(false, adapter.matches("RaceBox"))
        assertEquals(false, adapter.matches("KS-S22"))
        assertEquals(false, adapter.matches(""))
    }

    @Test
    fun decode_emptyNotification_returnsNull() {
        assertNull(adapter.decode(ByteArray(0)))
    }

    @Test
    fun decode_headerOnly_standalone_returnsEmptyList() {
        // Header _2 (final / standalone) with no payload triplets means the
        // radar saw no vehicles this tick. Empty list (lane clear), not null.
        val result = adapter.decode(bytes(0x02))
        assertEquals(emptyList<DecodedThreat>(), result)
    }

    @Test
    fun decode_standaloneFrame_oneCar() {
        // Header 0x02 (final fragment, standalone): one car id=1, 30 m, 25 km/h.
        val result = adapter.decode(bytes(0x02, 1, 30, 25))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(id = 1, distanceM = 30, approachSpeedKmh = 25), result[0])
    }

    @Test
    fun decode_standaloneFrame_threeCars() {
        val result = adapter.decode(
            bytes(
                0x02,
                1, 30, 25,
                2, 60, 40,
                3, 90, 55
            )
        )
        assertNotNull(result)
        assertEquals(3, result!!.size)
        assertEquals(DecodedThreat(1, 30, 25), result[0])
        assertEquals(DecodedThreat(2, 60, 40), result[1])
        assertEquals(DecodedThreat(3, 90, 55), result[2])
    }

    @Test
    fun decode_sequenceCounterInHighNibble_isIgnored() {
        // Header high nibble is a wrapping seq counter, low nibble is the
        // fragment flag. 0x52 should be parsed identically to 0x02.
        val result = adapter.decode(bytes(0x52, 1, 30, 25))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(1, 30, 25), result[0])
    }

    @Test
    fun decode_paddingTriple_isStripped() {
        // Varia sometimes pads the trailing triple with (0,0,0). Skip those
        // so the UI doesn't show ghost cars at 0 m.
        val result = adapter.decode(
            bytes(
                0x02,
                1, 30, 25,
                0, 0, 0
            )
        )
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(1, 30, 25), result[0])
    }

    @Test
    fun decode_malformedPayload_returnsNull() {
        // Payload size not divisible by 3 = corrupt; parser bails rather
        // than guess.
        val result = adapter.decode(bytes(0x02, 1, 30))
        assertNull(result)
    }

    @Test
    fun decode_firstFragment_returnsNull_andBuffers() {
        // _0 alone means "more fragments coming". Decoder must not emit
        // anything yet ,  the partial 6-car list would cause the visible
        // 6/1/6/1 jumping the tester reported.
        val first = adapter.decode(
            bytes(
                0x00,
                1, 10, 20,
                2, 20, 25,
                3, 30, 30,
                4, 40, 35,
                5, 50, 40,
                6, 60, 45
            )
        )
        assertNull(first)
    }

    @Test
    fun decode_fragmentedFrame_reassembled() {
        // 7-car scene split across two notifications: _0 with 6 cars,
        // _2 with the 7th. Combined result has all seven.
        adapter.decode(
            bytes(
                0x00,
                1, 10, 20,
                2, 20, 25,
                3, 30, 30,
                4, 40, 35,
                5, 50, 40,
                6, 60, 45
            )
        )
        val combined = adapter.decode(bytes(0x02, 7, 70, 50))
        assertNotNull(combined)
        assertEquals(7, combined!!.size)
        assertEquals(DecodedThreat(1, 10, 20), combined[0])
        assertEquals(DecodedThreat(7, 70, 50), combined[6])
    }

    @Test
    fun decode_lostFinalFragment_doesNotTaintNextFrame() {
        // First _0 buffers six cars. Then BLE drops the _2 (the rider rolls
        // through a dead spot). The next logical frame arrives as a fresh
        // _0 with two cars + its own _2 with a third. The lost-buffer cars
        // must NOT survive into this frame.
        adapter.decode(
            bytes(
                0x00,
                1, 10, 20,
                2, 20, 25,
                3, 30, 30,
                4, 40, 35,
                5, 50, 40,
                6, 60, 45
            )
        )
        // (no _2 arrived; rider lost reception)
        // New logical frame begins:
        adapter.decode(
            bytes(
                0x00,
                10, 11, 12,
                11, 22, 23
            )
        )
        val combined = adapter.decode(bytes(0x02, 12, 33, 34))
        assertNotNull(combined)
        assertEquals(3, combined!!.size)
        assertEquals(DecodedThreat(10, 11, 12), combined[0])
        assertEquals(DecodedThreat(11, 22, 23), combined[1])
        assertEquals(DecodedThreat(12, 33, 34), combined[2])
    }

    @Test
    fun decode_lostFirstFragment_treatedAsStandalone() {
        // _2 arrives without a preceding _0 (rider missed the start of the
        // stream after pairing). Parse it as a standalone frame rather than
        // freezing the listener.
        val result = adapter.decode(bytes(0x02, 99, 50, 40))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(99, 50, 40), result[0])
    }

    @Test
    fun decode_unknownFragmentFlag_treatedAsStandalone_andClearsBuffer() {
        // First buffer a _0 (so we can confirm the buffer gets cleared).
        adapter.decode(bytes(0x00, 1, 10, 20))
        // Then an unknown flag (high-nibble seq + low nibble we haven't
        // seen). Adapter should treat as standalone for forward-compat with
        // future firmware revisions, but discard the buffered _0.
        val result = adapter.decode(bytes(0x05, 9, 50, 40))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(9, 50, 40), result[0])

        // Confirm the buffer was cleared by feeding a _2 with no preceding
        // _0 ,  it should be parsed standalone (no ghost car #1 in front).
        val next = adapter.decode(bytes(0x02, 9, 50, 40))
        assertNotNull(next)
        assertEquals(1, next!!.size)
        assertEquals(DecodedThreat(9, 50, 40), next[0])
    }

    @Test
    fun decode_byteValuesAreUnsignedRange() {
        // Distance up to 255 m, speed up to 255 km/h, id up to 255 ,  all
        // fit in u8, but Kotlin's Byte is signed, so the adapter must mask
        // with 0xFF. A naive sign-extend would interpret 0xFF as -1.
        val result = adapter.decode(bytes(0x02, 0xFF, 0xFF, 0xFE))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(DecodedThreat(id = 255, distanceM = 255, approachSpeedKmh = 254), result[0])
    }
}
