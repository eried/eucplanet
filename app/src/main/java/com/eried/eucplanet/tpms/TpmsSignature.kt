package com.eried.eucplanet.tpms

/**
 * How a tyre sensor is told apart from everything else advertising nearby.
 *
 * There is no list of addresses to match: these caps ship with whatever MAC
 * the factory burned in, and a rider's two sensors have nothing in common with
 * anyone else's. What they do have is a habit. A valve-cap TPMS repeats its
 * own MAC inside the payload so a receiver can tell four identical caps apart,
 * and a phone, a laptop or a pair of earbuds has no reason to do that.
 *
 * Identification deliberately does not depend on decoding the pressure. It
 * used to, and switching the decoder off after its reading was disproved
 * switched off finding sensors along with it: a scan that used to land on the
 * rider's cap suddenly ended with nothing found. Behaviour identifies the
 * device; the decode only says what it is measuring.
 */
object TpmsSignature {

    /**
     * True when [payloadHex] carries [address]'s own MAC, in either byte order
     * and anywhere in the packet.
     *
     * Both orders because both are real: the rule was first written as
     * "payload starts with the MAC", and the one sensor actually in hand puts
     * it reversed at the end. A signature written from one guess about layout
     * misses every unit that made a different choice.
     */
    fun looksLikeSensor(payloadHex: String, address: String?): Boolean {
        val mac = address?.replace(":", "")?.uppercase() ?: return false
        if (mac.length != 12) return false
        val hex = payloadHex.uppercase()
        val reversed = mac.chunked(2).reversed().joinToString("")
        return hex.contains(mac) || hex.contains(reversed)
    }
}
