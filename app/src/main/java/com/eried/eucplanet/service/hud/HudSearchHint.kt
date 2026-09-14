package com.eried.eucplanet.service.hud

/**
 * One local IPv4 network the phone is on, as HUD discovery sees it.
 *
 * [hotspot] comes from the interface name: the softAP interface is `ap0`,
 * `softap0`, `swlan0` or `wlan1` depending on the OEM, and it is the one
 * network a HUD set up "for the phone" ever joins. Everything else is the
 * phone being a client on somebody's WiFi.
 */
data class LocalNet(val iface: String, val cidr: String, val hotspot: Boolean) {
    override fun toString(): String = "$iface $cidr (${if (hotspot) "hotspot" else "WiFi"})"
}

/**
 * Why an empty search came up empty, as far as the phone can tell.
 *
 * The 2026-09-11 shop capture: 62 searches over 21 minutes, every channel
 * quiet, and the trace only said "Phone networks: [10.250.3.26/24]". That was
 * the shop's WiFi with the phone's hotspot off, so a HUD that joins the hotspot
 * had nothing to join. The rider found out by turning the hotspot on; the HUD
 * paired 78 s later, of which the phone needed 125 ms. The rest was the HUD's
 * own radio finding the new network.
 *
 * Nothing on the phone can shorten that, but it can stop being a mystery: name
 * the situation in the trace, and tell the rider while they wait.
 */
enum class HudSearchHint {
    /** Searching normally, or found. */
    NONE,
    /** No WiFi and no hotspot: there is no network a HUD could be on. */
    NO_NETWORK,
    /** The phone is a WiFi client and its hotspot is off. */
    WIFI_NO_HOTSPOT;

    companion object {
        fun of(nets: List<LocalNet>): HudSearchHint = when {
            nets.isEmpty() -> NO_NETWORK
            nets.none { it.hotspot } -> WIFI_NO_HOTSPOT
            else -> NONE
        }

        /** The softAP interface names seen across OEMs. Shared with the
         *  settings hint so both agree on what "hotspot on" means. */
        fun isHotspotInterface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("ap") || n.startsWith("softap") ||
                n.startsWith("swlan") || n == "wlan1"
        }

        /** The trace line: every network with its kind, then the hotspot verdict. */
        fun describe(nets: List<LocalNet>): String = when {
            nets.isEmpty() -> "Phone networks: none (no WiFi, no hotspot)"
            nets.any { it.hotspot } -> "Phone networks: ${nets.joinToString()}"
            else -> "Phone networks: ${nets.joinToString()}, hotspot off"
        }
    }
}
