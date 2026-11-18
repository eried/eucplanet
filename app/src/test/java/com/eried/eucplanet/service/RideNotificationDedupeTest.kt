package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ride notification is re-posted when it reads differently, and at least every 30 s. */
class RideNotificationDedupeTest {

    private val t0 = 2_000_000L
    private val shown = WheelService.notificationKey("Connected", "12 km/h | 84%", listOf("Disconnect", "Record"))

    @Test fun `the same text is held back below 30 s`() {
        assertFalse(WheelService.shouldPostNotification(shown, shown, t0 + 29_999L, t0))
    }

    @Test fun `the same text is re-posted at 30 s`() {
        assertTrue(WheelService.shouldPostNotification(shown, shown, t0 + 30_000L, t0))
    }

    @Test fun `changed text is posted at once`() {
        val next = WheelService.notificationKey("Connected", "13 km/h | 84%", listOf("Disconnect", "Record"))
        assertTrue(WheelService.shouldPostNotification(next, shown, t0 + 1L, t0))
    }

    @Test fun `a changed button label counts as a change`() {
        val next = WheelService.notificationKey("Connected", "12 km/h | 84%", listOf("Disconnect", "Stop"))
        assertNotEquals(shown, next)
    }

    @Test fun `the key joins title, text and buttons`() {
        assertEquals("Connected|12 km/h | 84%|Disconnect|Record", shown)
        assertEquals("null|null", WheelService.notificationKey(null, null, null))
    }

    @Test fun `the first post after a reset always goes out`() {
        assertTrue(WheelService.shouldPostNotification(shown, "", t0, t0))
    }
}
