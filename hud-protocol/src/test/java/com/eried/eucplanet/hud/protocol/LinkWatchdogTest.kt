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
        msSinceLastPhoneContact: Long? = null,
        persistedRecentLink: Boolean = false,
        msSinceStart: Long = 0L,
    ) = LinkHealth(
        serverAlive, associated, localIp,
        msSinceLastPhoneContact, persistedRecentLink, msSinceStart,
    )

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

    // ---- phone starvation (wrong-network trap) --------------------------

    @Test fun paired_phone_silent_past_threshold_is_STARVED() {
        // The 2026-07-08 log: WiFi-sharing bounce, HUD re-associated somewhere,
        // watchdog read HEALTHY forever while the phone probed a subnet the HUD
        // was not on. Total silence past the threshold must now demand action.
        assertEquals(
            LinkVerdict.STARVED,
            LinkWatchdog.assess(health(
                msSinceLastPhoneContact = LinkWatchdog.STARVED_AFTER_MS))
        )
    }

    @Test fun paired_phone_recently_silent_is_still_HEALTHY() {
        // A fresh disconnect (rider behind a wall, phone redialing) must not
        // trigger recovery: the searching phone re-contacts within ~20 s.
        assertEquals(
            LinkVerdict.HEALTHY,
            LinkWatchdog.assess(health(
                msSinceLastPhoneContact = LinkWatchdog.STARVED_AFTER_MS - 1))
        )
    }

    @Test fun never_paired_and_nothing_persisted_never_starves() {
        // Fresh install, rider has not connected a phone yet: sit quietly
        // forever, exactly the old behaviour. First-boot safety intact.
        assertEquals(
            LinkVerdict.HEALTHY,
            LinkWatchdog.assess(health(msSinceStart = Long.MAX_VALUE))
        )
    }

    @Test fun app_restart_with_persisted_pairing_starves_after_boot_grace() {
        // The restart trap from the 2026-07-08 log: rider killed and reopened
        // the HUD app mid-outage, which reset the recovery gate and parked
        // self-heal forever. With a persisted recent pairing the watchdog now
        // arms itself once the boot grace has passed.
        assertEquals(
            LinkVerdict.HEALTHY,
            LinkWatchdog.assess(health(
                persistedRecentLink = true,
                msSinceStart = LinkWatchdog.RESTART_BOOT_GRACE_MS - 1))
        )
        assertEquals(
            LinkVerdict.STARVED,
            LinkWatchdog.assess(health(
                persistedRecentLink = true,
                msSinceStart = LinkWatchdog.RESTART_BOOT_GRACE_MS))
        )
    }

    @Test fun off_air_dominates_starvation() {
        // No association at all is OFF_AIR even when starved: the aggressive
        // off-air ladder (not the paced starved one) is the right response.
        assertEquals(
            LinkVerdict.OFF_AIR,
            LinkWatchdog.assess(health(
                associated = false,
                msSinceLastPhoneContact = LinkWatchdog.STARVED_AFTER_MS))
        )
    }

    @Test fun wedged_server_dominates_starvation() {
        // A dead listener explains the silence by itself; restart it first.
        assertEquals(
            LinkVerdict.SERVER_WEDGED,
            LinkWatchdog.assess(health(
                serverAlive = false,
                msSinceLastPhoneContact = LinkWatchdog.STARVED_AFTER_MS))
        )
    }

    // ---- recovery arming across app restarts -----------------------------

    @Test fun recovery_arms_on_healthy_link_this_run() {
        assertEquals(true, LinkWatchdog.recoveryArmed(
            everHealthyThisRun = true, persistedRecentLink = false, msSinceStart = 0L))
    }

    @Test fun recovery_arms_from_persistence_after_boot_grace() {
        assertEquals(false, LinkWatchdog.recoveryArmed(
            everHealthyThisRun = false, persistedRecentLink = true,
            msSinceStart = LinkWatchdog.RESTART_BOOT_GRACE_MS - 1))
        assertEquals(true, LinkWatchdog.recoveryArmed(
            everHealthyThisRun = false, persistedRecentLink = true,
            msSinceStart = LinkWatchdog.RESTART_BOOT_GRACE_MS))
    }

    @Test fun recovery_never_arms_on_a_virgin_device() {
        assertEquals(false, LinkWatchdog.recoveryArmed(
            everHealthyThisRun = false, persistedRecentLink = false,
            msSinceStart = Long.MAX_VALUE))
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

    @Test fun starved_ladder_goes_straight_to_the_toggle() {
        // Starved = associated AND the loopback server answers, so a phone on
        // our network would have found us by direct TCP probe; restarting
        // sockets can't help. Only re-picking the network can: toggle first,
        // reassociate fills the gaps, alternating forever.
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.starvedStepFor(0))
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.starvedStepFor(1))
        assertEquals(RecoveryStep.TOGGLE_WIFI, LinkWatchdog.starvedStepFor(2))
        assertEquals(RecoveryStep.REASSOCIATE, LinkWatchdog.starvedStepFor(3))
    }
}
