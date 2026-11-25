package com.eried.eucplanet.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHoldersTest {

    @Test
    fun `closing the window leaves the voice to the service that is still running`() {
        val holders = VoiceHolders()
        holders.add("window")
        holders.add("service")
        assertFalse("the ride goes on, the voice must stay", holders.remove("window"))
        assertTrue("the service was the last one", holders.remove("service"))
    }

    @Test
    fun `the service stopping leaves the voice to the open window`() {
        val holders = VoiceHolders()
        holders.add("window")
        holders.add("service")
        assertFalse(holders.remove("service"))
        assertTrue(holders.remove("window"))
    }

    @Test
    fun `with only the window it is shut down when the window goes`() {
        val holders = VoiceHolders()
        holders.add("window")
        assertTrue(holders.remove("window"))
    }

    @Test
    fun `one that never held it cannot shut it down`() {
        val holders = VoiceHolders()
        holders.add("window")
        assertFalse(holders.remove("service"))
        assertFalse("nor twice", VoiceHolders().remove("window"))
    }

    @Test
    fun `coming back counts once`() {
        val holders = VoiceHolders()
        holders.add("window")
        holders.add("window")
        holders.add("service")
        assertFalse(holders.remove("window"))
        assertTrue(holders.remove("service"))
    }
}
