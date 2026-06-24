package com.eried.eucplanet.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariaAdapterTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesStandaloneFrame() {
        val a = VariaAdapter()
        // header 0x02 (final/standalone) + two cars: (id5, 30 m, 25 km/h) and (id7, 80 m, 10).
        val out = a.decode(bytes(0x02, 5, 30, 25, 7, 80, 10))
        assertEquals(2, out!!.size)
        assertEquals(5, out[0].id); assertEquals(30, out[0].distanceM); assertEquals(25, out[0].approachSpeedKmh)
        assertEquals(7, out[1].id); assertEquals(80, out[1].distanceM)
    }

    @Test
    fun skipsZeroPadding() {
        val a = VariaAdapter()
        val out = a.decode(bytes(0x02, 5, 30, 25, 0, 0, 0))
        assertEquals(1, out!!.size)
        assertEquals(5, out[0].id)
    }

    @Test
    fun reassemblesFragments() {
        val a = VariaAdapter()
        // First fragment (flag 0) buffers, emits nothing.
        assertNull(a.decode(bytes(0x00, 1, 10, 5, 2, 20, 8)))
        // Final fragment (flag 2) glues the buffered cars + its own → 3 total.
        val out = a.decode(bytes(0x02, 3, 30, 12))
        assertEquals(3, out!!.size)
        assertEquals(1, out[0].id); assertEquals(2, out[1].id); assertEquals(3, out[2].id)
    }

    @Test
    fun rejectsMisalignedPayload() {
        val a = VariaAdapter()
        assertNull(a.decode(bytes(0x02, 5, 30))) // 2 payload bytes, not a multiple of 3
    }

    @Test
    fun matchesVariaNames() {
        val a = VariaAdapter()
        assertEquals(true, a.matches("RTL515 12345"))
        assertEquals(true, a.matches("Varia RCT715"))
        assertEquals(false, a.matches("RaceBox Mini 1234"))
    }
}
