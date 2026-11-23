package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glasses that drop come back on their own. */
class EngoLinkTest {

    @Test fun `a new screen waits while the glasses are far behind`() {
        assertTrue(EngoLink.roomFor(0))
        assertTrue(EngoLink.roomFor(EngoLink.MAX_BACKLOG - 1))
        assertFalse(EngoLink.roomFor(EngoLink.MAX_BACKLOG))
        assertFalse(EngoLink.roomFor(100_000))
    }

    @Test fun `the first search starts at once`() {
        assertTrue(EngoLink.searchDue(autoConnect = true, idle = true, sinceLastSearchMs = Long.MAX_VALUE))
    }

    @Test fun `a dropped link is searched for again after the wait`() {
        assertFalse(EngoLink.searchDue(true, true, EngoLink.SEARCH_EVERY_MS - 1))
        assertTrue(EngoLink.searchDue(true, true, EngoLink.SEARCH_EVERY_MS))
    }

    @Test fun `no search while one is running or the link is up`() {
        assertFalse(EngoLink.searchDue(true, idle = false, sinceLastSearchMs = Long.MAX_VALUE))
    }

    @Test fun `the display type comes from the hardware string`() {
        assertTrue(EngoLink.isColor("ALK03-MDP08ACRG"))
        assertFalse(EngoLink.isColor("ALK02-MDP05xxx"))
        // Unknown or unread: grey, which every pair can draw.
        assertFalse(EngoLink.isColor(""))
        assertFalse(EngoLink.isColor("ALK09-MDP11"))
    }

    @Test fun `glasses are known by name or by maker id`() {
        assertTrue(EngoLink.isGlasses("ENGO 2 090756", emptyList()))
        assertTrue(EngoLink.isGlasses("engo 3 123456", emptyList()))
        assertTrue(EngoLink.isGlasses("A.Look  090756", emptyList()))
        assertTrue(EngoLink.isGlasses(null, listOf(0x004C, EngoLink.MAKER_ID)))
        assertFalse(EngoLink.isGlasses("Varia RTL515", listOf(0x0087)))
        assertFalse(EngoLink.isGlasses(null, emptyList()))
    }

    @Test fun `no search when the rider does not want the link kept`() {
        assertFalse(EngoLink.searchDue(autoConnect = false, idle = true, sinceLastSearchMs = Long.MAX_VALUE))
    }
}
