package com.eried.eucplanet.hud.protocol

/**
 * Pure rules for spotting a HALF-OPEN HUD link before a heartbeat times out.
 *
 * A TCP socket whose peer went away without a FIN stays open on both ends. The
 * WebSocket layer on each side keeps reporting "connected", so both the phone's
 * dial loop and the HUD's self-heal watchdog believe the link is fine, and every
 * fast-re-pair mechanism either side has is gated behind a heartbeat expiring:
 *
 *  - HUD: Ktor ping 5 s, peer declared dead after 12 s of silence.
 *  - Phone: OkHttp ping 5 s in foreground, 15 s in background, so up to 30 s.
 *  - Phone: `webSocket.send()` keeps returning true for the better part of an
 *    hour, because OkHttp buffers to 16 MiB and a HUD frame is about a KB.
 *
 * The 2026-08-22 tester capture measured the cost. The HUD came back on a new
 * subnet and announced it at 15:12:47.745, kept announcing every 2 s, and the
 * phone (which was receiving those announcements) sat on the dead socket until
 * the HUD's 12 s timeout closed it at 15:12:59.05. It then re-paired in 573 ms.
 * The whole loss was detection, not discovery.
 *
 * Both sides already hold the evidence, they just were not reading it. These
 * are the two readings, kept pure so they are provable and shared:
 *
 *  - [hudAddressChanged], the HUD noticing its own address moved. A socket the
 *    phone opened to the old address cannot still be alive, so this is proof,
 *    not suspicion, and the HUD can close on it outright.
 *  - [beaconContradictsPeer], the phone hearing the HUD announce an address it
 *    is not connected to. That is grounds to go and CHECK the live peer, not to
 *    cut it: two HUD interfaces, or a beacon we misread, must never cost a
 *    healthy link. The caller probes the peer and only then decides.
 */
object StaleLink {

    /**
     * True when this HUD's own IPv4 moved between [priorIp] and [currentIp],
     * which makes any socket a phone opened on the old address dead.
     *
     * Deliberately narrow. Only a move between two real addresses counts:
     *
     *  - a first address (blank prior) is a session starting, not a move, and
     *    treating it as one would drop the very first connection
     *  - a blank current is a lost lease, which belongs to [LinkWatchdog]'s
     *    OFF_AIR ladder. Acting on it here would churn the link every time a
     *    transient read came back empty.
     */
    fun hudAddressChanged(priorIp: String?, currentIp: String?): Boolean {
        val prior = priorIp?.trim().orEmpty()
        val current = currentIp?.trim().orEmpty()
        if (prior.isEmpty() || current.isEmpty()) return false
        return prior != current
    }

    /**
     * True when a discovery beacon names an address that is not the one the
     * live WebSocket is talking to, so the live socket is worth verifying.
     *
     * [currentPeer] is the dial loop's `host:port`. Anything we cannot read
     * cleanly returns false: cutting a live link over a value we failed to
     * parse is the worst trade available here, so silence is the safe answer.
     */
    fun beaconContradictsPeer(currentPeer: String?, beaconIp: String, beaconPort: Int): Boolean {
        val peer = currentPeer?.trim().orEmpty()
        if (peer.isEmpty()) return false
        val ip = beaconIp.trim()
        if (ip.isEmpty()) return false
        val sep = peer.lastIndexOf(':')
        if (sep <= 0 || sep == peer.length - 1) return false
        val peerHost = peer.substring(0, sep)
        val peerPort = peer.substring(sep + 1).toIntOrNull() ?: return false
        return peerHost != ip || peerPort != beaconPort
    }
}
