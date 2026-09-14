package com.eried.eucplanet.service.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone can tell, from its own interfaces, why a search found nothing.
 *
 * Built from the 2026-09-11 shop capture: the phone sat on the shop's WiFi
 * as a client (10.250.3.26/24, wlan0) with its hotspot off for 21 minutes,
 * and the trace said only "Phone networks: [10.250.3.26/24]". The HUD in that
 * shop joins the phone's hotspot, so it had nothing to join; the moment the
 * hotspot came up (10.106.212.20/24 on the softAP interface) and the HUD's
 * radio found it, the phone paired in 125 ms.
 */
class HudSearchHintTest {

    private val shopWifi = LocalNet("wlan0", "10.250.3.26/24", hotspot = false)
    private val hotspot = LocalNet("swlan0", "10.106.212.20/24", hotspot = true)

    @Test fun `a WiFi client with the hotspot off is the shop situation`() {
        assertEquals(HudSearchHint.WIFI_NO_HOTSPOT, HudSearchHint.of(listOf(shopWifi)))
    }

    @Test fun `the hotspot being up means the search is simply still searching`() {
        assertEquals(HudSearchHint.NONE, HudSearchHint.of(listOf(hotspot)))
        // Both at once (Wi-Fi sharing): still nothing the phone can add.
        assertEquals(HudSearchHint.NONE, HudSearchHint.of(listOf(shopWifi, hotspot)))
    }

    @Test fun `no interface at all is its own verdict`() {
        assertEquals(HudSearchHint.NO_NETWORK, HudSearchHint.of(emptyList()))
    }

    @Test fun `the trace line names the interface, its kind, and the hotspot verdict`() {
        assertEquals(
            "Phone networks: wlan0 10.250.3.26/24 (WiFi), hotspot off",
            HudSearchHint.describe(listOf(shopWifi)),
        )
        assertEquals(
            "Phone networks: swlan0 10.106.212.20/24 (hotspot)",
            HudSearchHint.describe(listOf(hotspot)),
        )
        assertEquals(
            "Phone networks: none (no WiFi, no hotspot)",
            HudSearchHint.describe(emptyList()),
        )
    }

    @Test fun `softAP interface names across OEMs read as the hotspot`() {
        for (name in listOf("ap0", "softap0", "swlan0", "wlan1", "AP0")) {
            assertTrue("$name is a hotspot interface", HudSearchHint.isHotspotInterface(name))
        }
        for (name in listOf("wlan0", "rmnet0", "lo", "")) {
            assertFalse("$name is not a hotspot interface", HudSearchHint.isHotspotInterface(name))
        }
    }
}
