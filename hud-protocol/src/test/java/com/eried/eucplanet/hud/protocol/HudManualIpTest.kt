package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual HUD address is written on every keystroke, so whatever the rider
 * had typed when they stopped becomes a saved setting. A real capture carried
 * `10.240.` for months: discovery dialled it every cycle, failed to resolve it,
 * and backed off five seconds, while the HUD sat on the LAN announcing itself.
 */
class HudManualIpTest {

    @Test
    fun `a complete address is usable`() {
        assertTrue(HudDiscovery.isValidIpv4("10.223.143.125"))
        assertTrue(HudDiscovery.isValidIpv4("192.168.43.42"))
        assertTrue(HudDiscovery.isValidIpv4("0.0.0.0"))
        assertTrue(HudDiscovery.isValidIpv4("255.255.255.255"))
        assertTrue(HudDiscovery.isValidIpv4("  10.0.0.1  "))
    }

    @Test
    fun `the address from the shop capture is rejected`() {
        assertFalse(HudDiscovery.isValidIpv4("10.240."))
    }

    @Test
    fun `every prefix of a real address is rejected while it is still being typed`() {
        // Each of these is a moment mid-typing, and each was previously saved
        // and dialled as if it were an address.
        for (partial in listOf("1", "10", "10.", "10.2", "10.22", "10.223.", "10.223.143")) {
            assertFalse(partial, HudDiscovery.isValidIpv4(partial))
        }
    }

    @Test
    fun `octets outside a byte are rejected`() {
        assertFalse(HudDiscovery.isValidIpv4("10.223.143.256"))
        assertFalse(HudDiscovery.isValidIpv4("999.1.1.1"))
        assertFalse(HudDiscovery.isValidIpv4("10.223.143.1234"))
    }

    @Test
    fun `malformed shapes are rejected`() {
        assertFalse(HudDiscovery.isValidIpv4(""))
        assertFalse(HudDiscovery.isValidIpv4("   "))
        assertFalse(HudDiscovery.isValidIpv4("10.223.143.125.9"))
        assertFalse(HudDiscovery.isValidIpv4("10..143.125"))
        assertFalse(HudDiscovery.isValidIpv4("10.223.143."))
        assertFalse(HudDiscovery.isValidIpv4(".10.223.143"))
    }
}
