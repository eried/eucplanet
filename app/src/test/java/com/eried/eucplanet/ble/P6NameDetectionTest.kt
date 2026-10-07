package com.eried.eucplanet.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a wheel is a P6 is decided from its BLE name, and everything hangs
 * off it.
 *
 * The P6 answers a different command set: the legacy queries return all-zero
 * blobs, so the adapter has to know before it sends its first packet. Get it
 * wrong and the wheel is still routed to this adapter, still polled and still
 * decoded, as a V-series at V-series offsets. That shows up as phase amps
 * stuck at zero, nonsense temperatures, the wrong speed-dial range, and the
 * "preliminary wheel" banner on a wheel that is on the verified list.
 *
 * The old rule was `startsWith("P6-")`: one spelling, case-sensitive, hyphen
 * required, and only at the very start.
 */
class P6NameDetectionTest {

    private fun isP6(name: String?) = InMotionV2Adapter.isP6NameForTest(name)

    @Test fun `the spelling the adapter was written for still works`() {
        assertTrue(isP6("P6-12345678"))
    }

    @Test fun `the spellings it used to miss`() {
        // Case: BLE names come back from the OS however the firmware wrote them.
        assertTrue(isP6("p6-12345678"))
        // Separators: a hyphen is not the only thing a firmware puts there.
        assertTrue(isP6("P6_12345678"))
        assertTrue(isP6("P6 12345678"))
        // Prefixed: the name is not always the first thing in the string.
        assertTrue(isP6("InMotion P6-12345678"))
        // Bare.
        assertTrue(isP6("P6"))
    }

    @Test fun `other wheels are still other wheels`() {
        // A letter in front means it is part of another word.
        assertFalse(isP6("MSP6"))
        assertFalse(isP6("msp6-1234"))
        // A digit straight after means it is part of a serial.
        assertFalse(isP6("ADVENTURE-P61234"))
        assertFalse(isP6("V14 50S"))
        assertFalse(isP6("Adventure-E0000298"))
        assertFalse(isP6("Sherman"))
        assertFalse(isP6("KS-16X"))
    }

    @Test fun `no name is not a P6, which is the case that started this`() {
        // A missing name routes to this adapter anyway, and silently takes the
        // V-series path. Nothing here can fix that; it is why the decision is
        // written to the diagnostics log.
        assertFalse(isP6(null))
        assertFalse(isP6(""))
        assertFalse(isP6("   "))
    }
}
