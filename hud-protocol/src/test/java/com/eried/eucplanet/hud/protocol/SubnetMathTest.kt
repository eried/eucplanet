package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "is the HUD's network even here?" predicate. The interesting cases are
 * the hotspot-gone fingerprint (HUD's last IP not covered by any scanned
 * subnet) and its inverse (still covered = keep quiet, the HUD is just
 * stranded or slow -- the HUD-side ladder handles that half).
 */
class SubnetMathTest {

    @Test fun hud_ip_inside_the_hotspot_subnet_is_present() {
        // Classic Samsung softAP: phone 192.168.183.1/24, HUD .87.
        assertTrue(SubnetMath.ipInAnyCidr(
            "192.168.183.87", listOf("192.168.183.1/24")))
    }

    @Test fun hud_ip_with_hotspot_gone_is_absent() {
        // Wi-Fi sharing bounce, softAP never returned: only the home STA
        // subnet remains. The HUD's old address matches nothing.
        assertFalse(SubnetMath.ipInAnyCidr(
            "192.168.183.87", listOf("192.168.1.23/24", "10.215.173.1/30")))
    }

    @Test fun any_matching_subnet_counts() {
        assertTrue(SubnetMath.ipInAnyCidr(
            "192.168.183.87",
            listOf("192.168.1.23/24", "192.168.183.1/24")))
    }

    @Test fun wider_prefixes_match_wider() {
        assertTrue(SubnetMath.ipInAnyCidr("10.1.2.3", listOf("10.99.0.1/8")))
        assertFalse(SubnetMath.ipInAnyCidr("11.1.2.3", listOf("10.99.0.1/8")))
    }

    @Test fun slash32_matches_only_itself() {
        assertTrue(SubnetMath.ipInAnyCidr("10.0.0.5", listOf("10.0.0.5/32")))
        assertFalse(SubnetMath.ipInAnyCidr("10.0.0.6", listOf("10.0.0.5/32")))
    }

    @Test fun slash0_matches_everything() {
        assertTrue(SubnetMath.ipInAnyCidr("1.2.3.4", listOf("9.9.9.9/0")))
    }

    @Test fun malformed_input_is_never_present() {
        assertFalse(SubnetMath.ipInAnyCidr("not-an-ip", listOf("192.168.1.1/24")))
        assertFalse(SubnetMath.ipInAnyCidr("192.168.1.300", listOf("192.168.1.1/24")))
        assertFalse(SubnetMath.ipInAnyCidr("192.168.1.5", listOf("garbage", "192.168.1.1/", "/24")))
        assertFalse(SubnetMath.ipInAnyCidr("192.168.1.5", emptyList()))
    }
}
