package com.eried.eucplanet.data.repository

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class IncomingShareParseTest {

    @Test
    fun geoQueryNameInBracketsLabelsTheStop() {
        val p = IncomingShareRepository.parse("geo:0,0?q=59.9139,10.7522(Oslo)")!!
        assertEquals(59.9139, p.lat!!, absoluteTolerance = 1e-9)
        assertEquals(10.7522, p.lng!!, absoluteTolerance = 1e-9)
        assertEquals("Oslo", p.label)
    }

    @Test
    fun encodedNameWithSpacesIsDecoded() {
        val p = IncomingShareRepository.parse("geo:59.9,10.7?q=59.9,10.7%20(Oslo%20Sentrum)")!!
        assertEquals("Oslo Sentrum", p.label)
    }

    @Test
    fun geoWithoutNameHasNoLabel() {
        val p = IncomingShareRepository.parse("geo:0,0?q=59.9139,10.7522")!!
        assertNull(p.label)
        assertNull(IncomingShareRepository.parse("geo:59.9139,10.7522")!!.label)
    }

    @Test
    fun emptyBracketsHaveNoLabel() {
        assertNull(IncomingShareRepository.parse("geo:0,0?q=59.9139,10.7522()")!!.label)
    }

    @Test
    fun geoQueryWithOnlyANameIsASearch() {
        val p = IncomingShareRepository.parse("geo:0,0?q=Oslo+Sentralstasjon")!!
        assertNull(p.lat)
        assertEquals("Oslo Sentralstasjon", p.query)
    }
}
