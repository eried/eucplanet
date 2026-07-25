package com.eried.eucplanet.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundTest {

    @After
    fun drain() {
        // Reset the singleton to background between tests (it's process-wide).
        repeat(5) { AppForeground.onActivityStopped() }
    }

    @Test
    fun `starts in background`() {
        assertFalse(AppForeground.isForeground.value)
    }

    @Test
    fun `a started activity brings the app to foreground`() {
        AppForeground.onActivityStarted()
        assertTrue(AppForeground.isForeground.value)
    }

    @Test
    fun `foreground stays true until the last activity stops`() {
        // Two overlapping activities (e.g. an activity launching another).
        AppForeground.onActivityStarted()
        AppForeground.onActivityStarted()
        AppForeground.onActivityStopped()
        assertTrue("still foreground with one activity up", AppForeground.isForeground.value)
        AppForeground.onActivityStopped()
        assertFalse("background once all activities stopped", AppForeground.isForeground.value)
    }

    @Test
    fun `extra stops never drive the counter negative`() {
        AppForeground.onActivityStopped()
        AppForeground.onActivityStopped()
        AppForeground.onActivityStarted()
        assertTrue(AppForeground.isForeground.value)
    }
}
