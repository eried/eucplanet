package com.eried.eucplanet.hud.protocol

/**
 * Pure decision core for the HUD-side link watchdog.
 *
 * Background: the HUD's old self-heal watchdog only TCP-probed `127.0.0.1`,
 * which always succeeds while the in-process Ktor server object is alive --
 * completely independent of whether `wlan0` is still associated to the
 * rider's phone hotspot. So when the phone left home-WiFi range and the HUD's
 * radio dropped off the softAP, the watchdog stayed green, took no action, and
 * the rider had to reboot the Motoeye. (Diagnosed from the
 * 2026-06-29 tester log: the HUD's UDP beacon vanished at 07:18:52 and never
 * returned.)
 *
 * This class turns the raw radio/association/peer signals into a single
 * verdict the HUD acts on. It is deliberately pure (plain values, no Android
 * radio) so the logic is unit-testable; the HUD only has to GATHER the signals
 * (see [LinkHealth]) and EXECUTE the verdict.
 */
object LinkWatchdog {

    /**
     * Classify the link from one tick's worth of signals.
     *
     * Precedence is the whole point: [LinkVerdict.OFF_AIR] dominates
     * [LinkVerdict.SERVER_WEDGED], because restarting the loopback-bound Ktor
     * server is useless when the radio can't be reached over the air -- we must
     * reassociate first.
     */
    fun assess(h: LinkHealth): LinkVerdict {
        val hasIp = !h.localIp.isNullOrBlank()
        // No IP (lost DHCP lease) or no association == we cannot be reached over
        // the radio. A server restart would not help; reassociate.
        if (!hasIp || !h.associated) return LinkVerdict.OFF_AIR
        // We had a real link to this phone and can no longer reach it: off-air
        // in practice even if the association flag still reads connected. Only
        // consulted once we actually know a peer (avoids a first-boot storm).
        if (h.peerKnown && !h.peerReachable) return LinkVerdict.OFF_AIR
        // Radio is fine. NOW the loopback server-liveness matters: a wedged
        // listener with a healthy radio is the one case a server restart fixes.
        if (!h.serverAlive) return LinkVerdict.SERVER_WEDGED
        return LinkVerdict.HEALTHY
    }

    /**
     * The recovery action to take for the Nth consecutive off-air recovery
     * attempt. Escalates cheap -> decisive and then alternates forever rather
     * than ever surrendering -- the only alternative to "keep trying" is the
     * rider rebooting the HUD, which is the failure we are removing.
     *
     *   0      -> [RecoveryStep.RESTART_SOCKETS]  (re-resolve IP, restart
     *             beacon/mDNS/finder, bounce the WiFi lock; no radio state change)
     *   odd    -> [RecoveryStep.TOGGLE_WIFI]      (off/on; the DECISIVE rung,
     *             front-loaded because a Motoeye E6 field log showed reassociate
     *             alone did not clear the off-air state -- the toggle did. Only
     *             effective pre-API29; the caller falls back to REASSOCIATE where
     *             it is a no-op)
     *   even>0 -> [RecoveryStep.REASSOCIATE]      (WifiManager.reconnect/reassociate;
     *             cheap, fills the gaps, and the only effective path on API29+)
     */
    fun recoveryStepFor(attempt: Int): RecoveryStep = when {
        attempt <= 0 -> RecoveryStep.RESTART_SOCKETS
        attempt % 2 == 1 -> RecoveryStep.TOGGLE_WIFI
        else -> RecoveryStep.REASSOCIATE
    }
}

/**
 * One tick's worth of HUD link-health signals, gathered by the HUD before
 * calling [LinkWatchdog.assess]. Plain values only so the verdict is testable.
 */
data class LinkHealth(
    /** The in-process Ktor server answered a loopback TCP probe. True whenever
     *  the server object is alive regardless of the radio, so it is a WEAK
     *  signal on its own -- only meaningful once the radio is confirmed up. */
    val serverAlive: Boolean,
    /** `wlan0` reports a completed association: SupplicantState COMPLETED, a
     *  non-null BSSID, and a non-zero DHCP address. */
    val associated: Boolean,
    /** Current non-loopback IPv4, or null/blank when the DHCP lease is gone. */
    val localIp: String?,
    /** We have paired with a phone this session, so [peerReachable] is
     *  meaningful. Before the first pair it is false and the peer probe is
     *  ignored. */
    val peerKnown: Boolean,
    /** An on-network reachability probe to the last-seen phone succeeded. Only
     *  consulted when [peerKnown]. */
    val peerReachable: Boolean,
)

/** What [LinkWatchdog.assess] decided about the link this tick. */
enum class LinkVerdict {
    /** Associated, has an IP, and (if known) the peer answers. Reset streaks. */
    HEALTHY,
    /** Radio is fine but the Ktor listener is wedged: restart the server only. */
    SERVER_WEDGED,
    /** Off the air -- no IP, no association, or the peer is unreachable. The
     *  radio must be reassociated; a server restart would not help. */
    OFF_AIR,
}

/** A rung on the off-air recovery ladder, chosen by [LinkWatchdog.recoveryStepFor]. */
enum class RecoveryStep {
    /** Cheapest: re-resolve the local IP and restart the beacon / mDNS / phone
     *  finder, bouncing the WiFi lock. No radio state change. */
    RESTART_SOCKETS,
    /** WifiManager.reconnect() + reassociate() to kick the supplicant into
     *  rejoining the hotspot. Needs CHANGE_WIFI_STATE. */
    REASSOCIATE,
    /** Heaviest: toggle WiFi off then on. Only effective on pre-API29 devices
     *  (setWifiEnabled is a no-op for apps on API29+); the caller degrades to
     *  REASSOCIATE where the toggle cannot run. */
    TOGGLE_WIFI,
}
