package com.eried.eucplanet.tpms

/**
 * A tyre cap's own MAC, read out of what it broadcasts, for a phone that never
 * shows a peripheral's MAC.
 *
 * iOS hands the app a per-phone UUID instead of the address, so
 * [TpmsSignature.endsWithOwnMac] could never match and an LY cap would never
 * be found, let alone decoded. Both known families carry the MAC in the
 * payload, so the iPhone's radio (IosTpmsRadio) takes it from there: the
 * shared rules run unchanged, and a cap has the same id (and the same short
 * id on its row) as on Android, kept across an iPhone reset that would hand
 * out a new UUID.
 *
 * The cost: for the LY family the "MAC written backwards in the last six
 * bytes" check then compares the payload with itself and always passes, so on
 * iOS identification rests on the company id and the exact length alone (and
 * on the decode for a reading). Android is unaffected: it never calls this.
 */
object TpmsPayloadAddress {

    /**
     * The MAC this payload carries, "AA:BB:CC:DD:EE:FF" in capitals as Android
     * prints it, or null when it is not one of the known families.
     *
     *  - LY / Wicarlink (company 0x00AC, 15 bytes): the last six bytes, back to
     *    front, which is how Android's address compares in [TpmsSignature].
     *  - ZEEPIN (company 0x0001, 16 bytes, first byte 0x80..0x83): bytes 0..5
     *    in order. The wheel number doubles as the MAC's first byte, as the
     *    open source readers of this family publish it (80:EA:CA:.. for wheel
     *    one), so this is the address Android sees too.
     */
    fun fromManufacturer(companyId: Int, payload: ByteArray): String? = when {
        companyId == TpmsSignature.COMPANY_ID && payload.size == TpmsSignature.PAYLOAD_LEN ->
            mac(payload.copyOfRange(payload.size - 6, payload.size).reversedArray())
        companyId == ZeepinTpmsDecoder.COMPANY_ID && payload.size == ZeepinTpmsDecoder.PAYLOAD_LEN &&
            (payload[0].toInt() and 0xFF) in 0x80..0x83 ->
            mac(payload.copyOfRange(0, 6))
        else -> null
    }

    /** The first MAC any of [manufacturer]'s entries carries, or null. */
    fun of(manufacturer: List<Pair<Int, ByteArray>>): String? =
        manufacturer.firstNotNullOfOrNull { (id, payload) -> fromManufacturer(id, payload) }

    /**
     * Whether [address] is one of [filter], ignoring case; a null filter lets
     * everything through. For a radio whose platform cannot filter by address
     * (CoreBluetooth never sees one), so it filters what it heard instead.
     */
    fun passes(address: String, filter: Collection<String>?): Boolean =
        filter == null || filter.any { it.equals(address, ignoreCase = true) }

    private fun mac(bytes: ByteArray): String =
        bytes.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()
}
