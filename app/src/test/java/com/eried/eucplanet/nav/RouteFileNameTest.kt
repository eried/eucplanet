package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFileNameTest {

    @Test
    fun `two named stops become first to last`() {
        assertEquals(
            "Storgata to Tromsdalen",
            RouteFileName.suggest(listOf("Storgata", "Tromsdalen"), fallback = "My route")
        )
    }

    @Test
    fun `single stop uses its name`() {
        assertEquals("Storgata", RouteFileName.suggest(listOf("Storgata"), fallback = "My route"))
    }

    @Test
    fun `blank endpoints fall back to route name`() {
        assertEquals("My route", RouteFileName.suggest(listOf("", ""), fallback = "My route"))
    }

    @Test
    fun `illegal filename characters are stripped`() {
        assertEquals(
            "ab to cd",
            RouteFileName.suggest(listOf("a/b:", "c*d?"), fallback = "My route")
        )
    }
}
