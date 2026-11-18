package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glasses that drop come back on their own. */
class EngoLinkTest {

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

    @Test fun `no search when the rider does not want the link kept`() {
        assertFalse(EngoLink.searchDue(autoConnect = false, idle = true, sinceLastSearchMs = Long.MAX_VALUE))
    }
}
