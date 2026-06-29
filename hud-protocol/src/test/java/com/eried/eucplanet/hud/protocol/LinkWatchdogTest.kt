package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision matrix for the HUD-side link watchdog. The HUD's old watchdog only
 * TCP-probed 127.0.0.1, which stays green even when wlan0 has dropped off the
 * phone's hotspot -- so it never noticed an off-air radio and the rider had to
 * reboot the Motoeye. [LinkWatchdog.assess] is the pure replacement: given the
 * radio/association/peer signals it says whether we are HEALTHY, merely
 * SERVER_WEDGED (radio fine, listener dead), or OFF_AIR (must reassociate).
 *
 * Kept pure (plain values, no Android radio) so the verdict logic is provable
 * here; the HUD only has to gather the signals and act on the verdict.
 */
class LinkWatchdogTest {

    private fun health(
        serverAlive: Boolean = true,
        associated: Boolean = true,
        localIp: String? = "10.15.125.125",
        peerKnown: Boolean = true,
        peerReachable: Boolean = true,
    ) = LinkHealth(serverAlive, associated, localIp, peerKnown, peerReachable)

    @Test fun all_signals_good_is_HEALTHY() {
        assertEquals(LinkVerdict.HEALTHY, LinkWatchdog.assess(health()))
    }

    @Test fun lost_dhcp_lease_is_OFF_AIR_even_if_associated_flag_true() {
        // The 07:18:52 "beacon: no broadcast received" was the HUD losing its
        // lease: pickLocalIp() goes valid -> null. The old code dropped this
        // transition on the floor; here a blank IP is unambiguously off-air.
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(localIp = null)))
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(localIp = "   ")))
    }

    @Test fun not_associated_is_OFF_AIR() {
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(associated = false)))
    }

    @Test fun known_peer_unreachable_is_OFF_AIR() {
        // We had a healthy link to this phone; if we can no longer reach it,
        // we are effectively off-air regardless of what loopback says.
        assertEquals(LinkVerdict.OFF_AIR, LinkWatchdog.assess(health(peerReachable = false)))
    }

    @Test fun unknown_peer_is_not_treated_as_off_air() {
        // Before we have ever paired, an unreachable probe is meaningless and
        // must not false-positive into a reassociate storm.
        assertEquals(
            LinkVerdict.HEALTHY,
            LinkWatchdog.assess(health(peerKnown = false, peerReachable = false))
        )
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

    @Test fun ladder_starts_cheap_then_escalates_and_never_gives_up() {
        assertEquals(RecoveryStep.RESTART_SOCKETS, LinkWatchdog.recoveryStepFor(0))
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.recoveryStepFor(1))
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.recoveryStepFor(2))
        // Past the toggle we keep alternating reassociate/toggle forever rather
        // than surrendering -- the alternative is the rider rebooting anyway.
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.recoveryStepFor(3))
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.recoveryStepFor(4))
    }

    @Test fun ladder_clamps_negative_to_first_step() {
        assertEquals(RecoveryStep.RESTART_SOCKETS, LinkWatchdog.recoveryStepFor(-5))
    }
}
