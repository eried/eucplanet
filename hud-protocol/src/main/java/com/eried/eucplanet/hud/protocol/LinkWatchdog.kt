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

    /** How long an associated HUD may go with ZERO phone contact before the
     *  association itself is suspect ([LinkVerdict.STARVED]). A searching
     *  phone runs a full discovery cycle (subnet TCP probe + mDNS + beacon
     *  listen) every ~20 s, and a reconnect on the right network completes
     *  within one cycle -- so one whole cycle of silence after a pairing
     *  means we are almost certainly associated to the WRONG network (the
     *  2026-07-08 tester log: WiFi-sharing bounce left the HUD "healthy" on
     *  another subnet while the phone probed forever). */
    const val STARVED_AFTER_MS: Long = 20_000L

    /** Boot / app-restart grace: with no link yet THIS process but a
     *  persisted recent pairing, leave the initial association to the OS for
     *  this long before arming recovery. Protects the first-boot join (the
     *  old churn bug) while still un-parking a HUD whose app was restarted
     *  mid-outage -- previously that parked recovery forever. */
    const val RESTART_BOOT_GRACE_MS: Long = 60_000L

    /** Minimum gap between starved-recovery rungs. Starved means the radio is
     *  nominally associated, so recovery must pace itself: each rung needs
     *  time to take effect, and a rider who simply quit the phone app should
     *  see a gentle retry, not a WiFi toggle every watchdog tick. */
    const val STARVED_RETRY_MS: Long = 20_000L

    /**
     * Classify the link from one tick's worth of signals, judged ONLY from the
     * HUD's own radio state.
     *
     * We deliberately do NOT probe the phone. The phone DIALS INTO the HUD (the
     * HUD is the server), so the HUD never needs to reach the phone; and a phone
     * in a pocket (screen off, power-save) routinely fails a reachability probe
     * while still able to dial in. An earlier "phone unreachable -> off-air"
     * rule made the HUD reboot its Wi-Fi driver in a loop during the first
     * connection. So if the HUD is associated and holds an IP, it is reachable:
     * sit quietly and let the phone connect.
     *
     * One exception, learned from the WiFi-sharing-bounce log: "associated
     * with an IP" only proves we are on SOME network, not the phone's. Once a
     * phone has paired (this process, or recently per persisted state), total
     * phone silence past [STARVED_AFTER_MS] flips the verdict to
     * [LinkVerdict.STARVED] so the caller climbs the same recovery ladder --
     * a WiFi toggle makes the supplicant re-pick the hotspot. The
     * sleeping-phone false positive stays fixed because a live phone that
     * merely dropped the WebSocket keeps probing us every ~20 s, which counts
     * as contact and resets the starvation clock upstream.
     *
     * Precedence: [LinkVerdict.OFF_AIR] dominates [LinkVerdict.SERVER_WEDGED] --
     * restarting the loopback-bound Ktor server is useless when the radio itself
     * is off the air; reassociate first.
     */
    fun assess(h: LinkHealth): LinkVerdict {
        val hasIp = !h.localIp.isNullOrBlank()
        // No IP (lost DHCP lease) or no association == we are off the hotspot.
        // A server restart would not help; reassociate.
        if (!hasIp || !h.associated) return LinkVerdict.OFF_AIR
        // Radio is fine. NOW the loopback server-liveness matters: a wedged
        // listener with a healthy radio is the one case a server restart fixes.
        if (!h.serverAlive) return LinkVerdict.SERVER_WEDGED
        // Radio and server fine, but a paired phone has gone completely
        // silent: we are probably on the wrong network.
        if (h.msSinceLastPhoneContact != null) {
            if (h.msSinceLastPhoneContact >= STARVED_AFTER_MS) return LinkVerdict.STARVED
        } else if (h.persistedRecentLink && h.msSinceStart >= RESTART_BOOT_GRACE_MS) {
            return LinkVerdict.STARVED
        }
        return LinkVerdict.HEALTHY
    }

    /**
     * The recovery action for the Nth starved attempt. Unlike the off-air
     * ladder there is no RESTART_SOCKETS rung: starved means the radio is
     * associated and the server answers loopback, so a phone on OUR network
     * would have found us by direct TCP probe regardless of beacon/mDNS
     * state. Refreshing sockets fixes nothing; only re-picking the network
     * can. Front-load the decisive toggle, alternate with reassociate.
     */
    fun starvedStepFor(attempt: Int): RecoveryStep =
        if (attempt % 2 == 0) RecoveryStep.TOGGLE_WIFI else RecoveryStep.REASSOCIATE

    /**
     * Whether the off-air recovery ladder may run at all. Armed once the link
     * has been healthy this process, OR when a recent pairing is persisted
     * and the boot grace has elapsed -- so an app restart during an outage no
     * longer parks recovery forever ("leaving initial association to the OS"
     * used to be terminal until the next healthy link that never came).
     */
    fun recoveryArmed(
        everHealthyThisRun: Boolean,
        persistedRecentLink: Boolean,
        msSinceStart: Long,
    ): Boolean = everHealthyThisRun ||
        (persistedRecentLink && msSinceStart >= RESTART_BOOT_GRACE_MS)

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
 *
 * Intentionally minimal: only the HUD's OWN radio state. No phone-reachability
 * probe (the phone dials in; probing it false-positives when the phone is
 * asleep -- see [LinkWatchdog.assess]).
 */
data class LinkHealth(
    /** The in-process Ktor server answered a loopback TCP probe. True whenever
     *  the server object is alive regardless of the radio, so it is a WEAK
     *  signal on its own -- only meaningful once the radio is confirmed up. */
    val serverAlive: Boolean,
    /** `wlan0` reports a completed association: SupplicantState COMPLETED plus a
     *  non-zero DHCP address. */
    val associated: Boolean,
    /** Current non-loopback IPv4, or null/blank when the DHCP lease is gone. */
    val localIp: String?,
    /** Milliseconds since the last phone contact THIS process (a WebSocket
     *  attach or detach), or null if no phone has connected this process.
     *  Monotonic-clock based. Drives [LinkVerdict.STARVED]. */
    val msSinceLastPhoneContact: Long? = null,
    /** A phone paired recently per persisted state (survives app restarts).
     *  Lets a restarted app arm recovery after [LinkWatchdog.RESTART_BOOT_GRACE_MS]
     *  instead of waiting forever for a first healthy link. */
    val persistedRecentLink: Boolean = false,
    /** Milliseconds since this server instance started. Monotonic. */
    val msSinceStart: Long = 0L,
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
    /** Associated with an IP, but a paired phone has been totally silent past
     *  [LinkWatchdog.STARVED_AFTER_MS]: we are likely on the WRONG network
     *  (classic after a hotspot bounce latches another saved SSID). Climb the
     *  recovery ladder, paced by [LinkWatchdog.STARVED_RETRY_MS]. */
    STARVED,
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
