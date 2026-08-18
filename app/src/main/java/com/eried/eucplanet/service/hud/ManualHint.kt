package com.eried.eucplanet.service.hud

import com.eried.eucplanet.hud.protocol.HudDiscovery

/**
 * Whether the rider's typed HUD address should be dialled this round.
 *
 * Pulled out of the discovery race so it can be tested, because both ways it
 * can go wrong cost a rider real time and neither is visible on screen. A
 * capture from a shop floor showed `10.240.` saved as the manual address:
 * discovery dialled it every cycle, failed to resolve it, and backed off five
 * seconds - twice - while the HUD sat on the same subnet announcing itself by
 * beacon. Twelve of the twenty seconds it took to connect were that.
 */
internal enum class ManualHintDecision {
    /** Not a complete address. Dialling it can only fail. */
    IGNORE_INCOMPLETE,

    /** The HUD is announcing itself right now; a typed address is a guess
     *  about where it used to be, so the beacon wins. */
    HOLD_FOR_BEACON,

    /** Nothing better on offer: dial what the rider typed. */
    USE,
}

internal object ManualHint {

    /**
     * [beaconAgeMs] is how long ago the last beacon arrived, or null if none
     * has. [freshnessMs] is how recent a beacon has to be to still count.
     */
    fun decide(manualIp: String, beaconAgeMs: Long?, freshnessMs: Long): ManualHintDecision = when {
        manualIp.isBlank() || !HudDiscovery.isValidIpv4(manualIp) ->
            ManualHintDecision.IGNORE_INCOMPLETE
        beaconAgeMs != null && beaconAgeMs < freshnessMs ->
            ManualHintDecision.HOLD_FOR_BEACON
        else -> ManualHintDecision.USE
    }
}
