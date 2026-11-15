package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that let either side notice a HALF-OPEN HUD link without waiting
 * for a heartbeat to time out.
 *
 * Background, from the 2026-08-22 tester capture (app 0.16.3, HUD 0.1.12): the
 * HUD's Wi-Fi re-formed on a new subnet, which left the phone holding a socket
 * to the old address. Neither side can tell a half-open socket from a live one,
 * so the re-pair could not start until the HUD's 12 s Ktor heartbeat expired.
 * The capture shows the cost exactly: the HUD announced its new address at
 * 15:12:47.745 and kept announcing it every 2 s, the phone ignored roughly six
 * of those, and the moment the socket finally closed the phone re-paired in
 * 573 ms. Discovery was never slow, only the dead-socket detection was.
 *
 * Both sides already hold the evidence that proves the socket is stale before
 * any timeout fires. This is that evidence turned into two plain predicates:
 *
 *  - the HUD knows its own address changed, so a socket the phone dialled on
 *    the old one cannot still be alive
 *  - the phone hears the HUD announce an address that is not the one it is
 *    connected to, which is grounds to go and check
 *
 * Kept pure (plain values, no Android radio, no sockets) so the rules are
 * provable and shared by both modules.
 */
class StaleLinkTest {

    // ---- HUD side: our own address moved out from under the live socket ----

    @Test fun hud_address_change_invalidates_the_live_socket() {
        // The capture's case. The phone dialled 10.240.x, the HUD is now on
        // 10.222.65.125, so whatever the phone is holding is dead by definition.
        assertTrue(StaleLink.hudAddressChanged("10.240.11.8", "10.222.65.125"))
    }

    @Test fun same_address_is_not_a_change() {
        assertFalse(StaleLink.hudAddressChanged("10.222.65.125", "10.222.65.125"))
    }

    @Test fun first_address_of_the_session_is_not_a_change() {
        // Boot, or a DHCP lease arriving for the first time. There was no link
        // to invalidate, and treating this as a change would drop the very
        // first connection.
        assertFalse(StaleLink.hudAddressChanged(null, "10.222.65.125"))
        assertFalse(StaleLink.hudAddressChanged("", "10.222.65.125"))
    }

    @Test fun losing_the_address_is_left_to_the_off_air_ladder() {
        // A blank current read is the OFF_AIR verdict's business, and acting on
        // it here would churn the link on a transient read. See LinkWatchdog.
        assertFalse(StaleLink.hudAddressChanged("10.222.65.125", null))
        assertFalse(StaleLink.hudAddressChanged("10.222.65.125", ""))
    }

    // ---- Phone side: the HUD is announcing an address we are not on ----

    @Test fun beacon_naming_another_address_contradicts_the_live_peer() {
        assertTrue(
            StaleLink.beaconContradictsPeer("10.240.11.8:28080", "10.222.65.125", 28080)
        )
    }

    @Test fun beacon_naming_another_port_contradicts_the_live_peer() {
        assertTrue(
            StaleLink.beaconContradictsPeer("10.222.65.125:28080", "10.222.65.125", 29000)
        )
    }

    @Test fun beacon_that_agrees_with_the_live_peer_is_not_a_contradiction() {
        // The healthy steady state: the HUD beacons every 2 s while we are
        // connected to exactly that address. This must stay silent, or we would
        // tear down a good link twice a second.
        assertFalse(
            StaleLink.beaconContradictsPeer("10.222.65.125:28080", "10.222.65.125", 28080)
        )
    }

    @Test fun no_live_peer_means_nothing_to_contradict() {
        // Discovery is already running and will use the sighting on its own.
        assertFalse(StaleLink.beaconContradictsPeer(null, "10.222.65.125", 28080))
        assertFalse(StaleLink.beaconContradictsPeer("", "10.222.65.125", 28080))
    }

    @Test fun an_unparseable_peer_is_never_cut() {
        // Cutting a live link on a value we failed to read would be the worst
        // possible trade. Anything we cannot parse is left alone.
        assertFalse(StaleLink.beaconContradictsPeer("10.222.65.125", "10.222.65.125", 28080))
        assertFalse(StaleLink.beaconContradictsPeer("garbage", "10.222.65.125", 28080))
        assertFalse(StaleLink.beaconContradictsPeer("10.0.0.1:notaport", "10.222.65.125", 28080))
    }

    @Test fun a_blank_beacon_address_proves_nothing() {
        assertFalse(StaleLink.beaconContradictsPeer("10.222.65.125:28080", "", 28080))
    }
}
