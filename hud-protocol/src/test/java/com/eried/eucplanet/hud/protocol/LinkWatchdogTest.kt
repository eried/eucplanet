package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision matrix for the HUD-side link watchdog. The HUD's old watchdog only
 * TCP-probed 127.0.0.1, which stays green even when wlan0 has dropped off the
 * phone's hotspot -- so it never noticed an off-air radio and the rider had to
 * reboot the Motoeye. [LinkWatchdog.assess] is the pure replacement: given the
 * radio signals it says whether we are HEALTHY, merely SERVER_WEDGED (radio
 * fine, listener dead), or OFF_AIR (must reassociate).
 *
 * The verdict is judged ONLY from the HUD's OWN radio state (association + IP).
 * We deliberately do NOT probe the phone: the phone DIALS INTO the HUD (the HUD
 * is the server), so the HUD never needs to reach the phone -- and a phone in a
 * pocket (screen off, power-save) routinely fails to answer a probe while still
 * perfectly able to dial in. Treating "phone unreachable" as off-air made the
 * HUD reboot its Wi-Fi driver in a loop during the first connection. So: if the
 * HUD is associated and has an IP, it is reachable -- sit quietly and let the
 * phone connect.
 *
 * Kept pure (plain values, no Android radio) so the verdict logic is provable.
 */
class LinkWatchdogTest {

    private fun health(
        serverAlive: Boolean = true,
        associated: Boolean = true,
        localIp: String? = "10.15.125.125",
    ) = LinkHealth(serverAlive, associated, localIp)

    @Test fun associated_with_ip_is_HEALTHY() {
        assertEquals(LinkVerdict.HEALTHY, LinkWatchdog.assess(health()))
    }

    @Test fun associated_with_ip_is_HEALTHY_even_if_phone_is_asleep() {
        // The whole point of the regression fix: an associated HUD with a valid
        // IP is reachable. Whether the phone (which dials IN) currently answers
        // is irrelevant and must NEVER drive recovery. No "peer" concept exists.
        assertEquals(LinkVerdict.HEALTHY, LinkWatchdog.assess(health()))
    }

    @Test fun lost_dhcp_lease_is_OFF_AIR() {
        // The 07:18:52 "beacon: no broadcast received" was the HUD losing its
        // lease: pickLocalIp() goes valid -> null. A blank IP is off-air.
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(localIp = null)))
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(localIp = "   ")))
    }

    @Test fun not_associated_is_OFF_AIR() {
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(associated = false)))
    }

    @Test fun radio_fine_but_server_dead_is_SERVER_WEDGED() {
        assertEquals(LinkVerdict.SERVER_WEDGED, LinkWatchdog.assess(health(serverAlive = false)))
    }

    @Test fun off_air_dominates_server_wedged() {
        // No IP AND the server probe also fails: reassociating must win over
        // restarting the loopback-bound Ktor server, which is useless off-air.
        assertEquals(
            LinkVerdict.OFF_AIR,
            LinkWatchdog.assess(health(serverAlive = false, localIp = null))
        )
    }

    // ---- recovery ladder ------------------------------------------------

    @Test fun ladder_tries_cheap_then_front_loads_the_decisive_toggle() {
        // One cheap socket restart first, then the WiFi toggle -- field logs
        // (Motoeye E6, Android 7/8) showed reassociate alone did NOT clear the
        // off-air state; the toggle is what recovered it, so it is reached on
        // the very next rung instead of third. Reassociate fills the gaps
        // (cheaper, and the effective path on API29+ where toggle is a no-op).
        assertEquals(RecoveryStep.RESTART_SOCKETS, LinkWatchdog.recoveryStepFor(0))
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.recoveryStepFor(1))
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.recoveryStepFor(2))
        // Keep alternating toggle/reassociate forever rather than surrendering
        // -- the alternative is the rider rebooting anyway.
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.recoveryStepFor(3))
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.recoveryStepFor(4))
    }

    @Test fun ladder_clamps_negative_to_first_step() {
        assertEquals(RecoveryStep.RESTART_SOCKETS, LinkWatchdog.recoveryStepFor(-5))
    }
}
