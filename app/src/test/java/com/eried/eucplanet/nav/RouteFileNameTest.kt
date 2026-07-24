package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFileNameTest {

    @Test
    fun `two named stops join first and last with a dash and route suffix`() {
        assertEquals(
            "storgata-tromsdalen-route",
            RouteFileName.suggest(listOf("Storgata", "Tromsdalen"))
        )
    }

    @Test
    fun `spaces within a name become underscores and everything is lowercased`() {
        assertEquals(
            "storgata_nord-tromsdalen_center-route",
            RouteFileName.suggest(listOf("Storgata Nord", "Tromsdalen Center"))
        )
    }

    @Test
    fun `single stop uses its name plus route suffix`() {
        assertEquals("storgata-route", RouteFileName.suggest(listOf("Storgata")))
    }

    @Test
    fun `all-blank stops yield route`() {
        assertEquals("route", RouteFileName.suggest(listOf("", "")))
    }

    @Test
    fun `only the named stop is used when the other is blank`() {
        assertEquals("ikea-route", RouteFileName.suggest(listOf("", "IKEA")))
    }

    @Test
    fun `illegal filename characters collapse to underscores`() {
        assertEquals(
            "a_b-c_d-route",
            RouteFileName.suggest(listOf("a/b:", "c*d?"))
        )
    }
}
