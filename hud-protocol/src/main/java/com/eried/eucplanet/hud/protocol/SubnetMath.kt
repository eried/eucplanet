package com.eried.eucplanet.hud.protocol

/**
 * Tiny IPv4 subnet arithmetic for the "is the HUD's network even here?"
 * check. When every discovery channel fails, the phone compares the last
 * IP it ever streamed to against the subnets it just scanned: if no local
 * interface covers that IP, the hotspot (or Wi-Fi sharing) the HUD lived
 * on is gone, and no amount of further searching can fix that -- only the
 * rider can, so the phone should say so instead of probing in silence
 * (2026-07-08 tester log: three minutes of silent searching while the
 * softAP had never come back after a Wi-Fi sharing toggle).
 *
 * Pure JVM (no Android types) so it is unit-testable from hud-protocol.
 */
object SubnetMath {

    /** True when [ip] falls inside any of [cidrs] (each "a.b.c.d/prefix").
     *  Malformed entries are skipped; a malformed [ip] is in nothing. */
    fun ipInAnyCidr(ip: String, cidrs: List<String>): Boolean {
        val bits = ipv4Bits(ip) ?: return false
        return cidrs.any { cidr ->
            val slash = cidr.lastIndexOf('/')
            if (slash <= 0) return@any false
            val base = ipv4Bits(cidr.substring(0, slash)) ?: return@any false
            val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return@any false
            if (prefix !in 0..32) return@any false
            // prefix 0 matches everything; avoid the undefined 32-bit shift.
            if (prefix == 0) return@any true
            val mask = -1 shl (32 - prefix)
            (bits and mask) == (base and mask)
        }
    }

    /** Dotted-quad IPv4 to its 32-bit value, or null when malformed. */
    private fun ipv4Bits(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var out = 0
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out = (out shl 8) or v
        }
        return out
    }
}
