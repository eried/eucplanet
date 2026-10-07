package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.LegalLockdownCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownCodeTest {

    @Test
    fun `hash is stable and not the plain pin`() {
        val h = LegalLockdownCode.hash("4821")
        assertEquals(h, LegalLockdownCode.hash("4821"))
        assertFalse(h.contains("4821"))
        assertEquals(32, h.length)
    }

    @Test
    fun `different pins hash differently`() {
        assertFalse(LegalLockdownCode.hash("4821") == LegalLockdownCode.hash("4822"))
    }

    @Test
    fun `matches accepts the right pin and rejects the wrong one`() {
        val h = LegalLockdownCode.hash("135790")
        assertTrue(LegalLockdownCode.matches("135790", h))
        assertFalse(LegalLockdownCode.matches("135791", h))
    }

    @Test
    fun `matches never accepts anything against a blank hash`() {
        assertFalse(LegalLockdownCode.matches("1234", ""))
        assertFalse(LegalLockdownCode.matches("", ""))
    }

    @Test
    fun `pin must be 4 to 8 digits`() {
        assertFalse(LegalLockdownCode.isValidPin("123"))
        assertTrue(LegalLockdownCode.isValidPin("1234"))
        assertTrue(LegalLockdownCode.isValidPin("12345678"))
        assertFalse(LegalLockdownCode.isValidPin("123456789"))
        assertFalse(LegalLockdownCode.isValidPin("12a4"))
        assertFalse(LegalLockdownCode.isValidPin(""))
    }
}
