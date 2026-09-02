package com.eried.eucplanet.tpms

/**
 * How a tyre sensor is told apart from everything else advertising nearby.
 *
 * There is no list of addresses to match: these caps ship with whatever MAC
 * the factory burned in, so identification has to come from the shape of what
 * they broadcast.
 *
 * The shape has to be narrow. "The payload repeats its own MAC" was tried and
 * was far too loose - one thirty second sweep of a single room turned up three
 * unrelated devices doing exactly that, and one of them got added as a rider's
 * tyre sensor. Requiring a decoded pressure instead was too tight in the other
 * direction: with the decoder switched off after its reading was disproved,
 * nothing could be added at all, and a rider whose sensor was ten centimetres
 * away watched the scan find nothing.
 *
 * What actually pins this family down is all three at once: the company id,
 * the exact payload length, and the MAC repeated backwards in the last six
 * bytes. Every packet ever captured from the rider's sensor matches all three;
 * nothing else seen in a room ever has.
 */
object TpmsSignature {

    /** The manufacturer id this family advertises under. */
    const val COMPANY_ID = 0x00AC

    /** Every packet from this family is exactly this long. */
    const val PAYLOAD_LEN = 15

    /**
     * True when this advertisement is a sensor of the known family.
     *
     * This is what may add a sensor. It deliberately says nothing about
     * pressure: a device can be positively identified while its reading is
     * still unreadable, and pairing it with no number beats both a wrong
     * number and a scan that finds nothing.
     */
    fun isSensor(companyId: Int, payload: ByteArray, address: String?): Boolean =
        // The documented family identifies itself by decoding, which is the
        // strongest proof there is: one packet yields a pressure, a
        // temperature, a battery level and a wheel number, and all four have
        // to land somewhere believable at once.
        ZeepinTpmsDecoder.pressureKpa(companyId, payload) != null ||
            // The rider's own, which is recognised by shape because its
            // reading cannot be read yet.
            (companyId == COMPANY_ID &&
                payload.size == PAYLOAD_LEN &&
                endsWithOwnMac(payload, address))

    /** Whether the last six bytes are [address]'s own MAC, written backwards. */
    fun endsWithOwnMac(payload: ByteArray, address: String?): Boolean {
        val mac = macBytes(address) ?: return false
        if (payload.size < 6) return false
        val tail = payload.toList().takeLast(6).map { it.toInt() and 0xFF }
        return tail == mac.reversed()
    }

    /**
     * A looser check: does this payload carry its own MAC at all, either way
     * round?
     *
     * Only for deciding what is worth keeping in the decode trail. It is not
     * proof of anything, which is the whole lesson of the device that got
     * adopted by mistake, so nothing is ever added on the strength of it.
     */
    fun looksLikeSensor(payloadHex: String, address: String?): Boolean {
        val mac = address?.replace(":", "")?.uppercase() ?: return false
        if (mac.length != 12) return false
        val hex = payloadHex.uppercase()
        val reversed = mac.chunked(2).reversed().joinToString("")
        return hex.contains(mac) || hex.contains(reversed)
    }

    private fun macBytes(address: String?): List<Int>? {
        val parts = address?.split(":") ?: return null
        if (parts.size != 6) return null
        return parts.map { it.toIntOrNull(16) ?: return null }
    }
}
