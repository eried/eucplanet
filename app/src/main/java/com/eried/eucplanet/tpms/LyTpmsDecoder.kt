package com.eried.eucplanet.tpms

/**
 * The generic valve-cap TPMS sold as "LY" and a dozen other names.
 *
 * It never pairs. It wakes, shouts one manufacturer-data blob under company id
 * 0x00AC and sleeps, so the only way to read it is to listen.
 *
 * DECODED FROM CAPTURES, not from a datasheet. A rider read their gauge while
 * the app logged every advertisement in range, at two pressures far enough
 * apart to be unambiguous:
 *
 *   5.3 bar (530 kPa)   ->  0A61 = 2657  ->  531.4 kPa
 *                           0A50 = 2640  ->  528.0 kPa
 *   78 psi  (538 kPa)   ->  0A80 = 2688  ->  537.6 kPa
 *                           0A78 = 2680  ->  536.0 kPa
 *
 * Both within one percent of the gauge, which is closer than the gauge.
 *
 * Layout, 15 bytes, offsets from the start of the manufacturer data:
 * ```
 *   0..1  counter, climbs steadily and rolls over
 *   2     steady at 0x51-0x52 across every capture; temperature, unconfirmed
 *   3     small, changes every packet; part of the counter or a status nibble
 *   4..5  PRESSURE, big-endian, units of 0.2 kPa   <- the one we are sure of
 *   6..7  varies with nothing else; checksum, unconfirmed
 *   8     constant 0x28
 *   9..11 constant 0x111111
 *  12..14 the sensor's own MAC, last three bytes, reversed
 * ```
 * Only the pressure is published. The rest is written down because the next
 * person to look at this should not have to re-derive what was already seen,
 * and marked unconfirmed because a plausible reading is not a decoded one.
 */
object LyTpmsDecoder {

    /** Bluetooth SIG company id this sensor advertises under. */
    const val COMPANY_ID = 0x00AC

    private const val LENGTH = 15
    private const val PRESSURE_AT = 4

    /** The value is in fifths of a kPa. */
    private const val PER_KPA = 5f

    /**
     * Above this, the packet is not a pressure.
     *
     * A wheel tyre runs somewhere under 6 bar and the sensors are rated to
     * about 8. The ceiling is here so a same-company-id device that is not a
     * TPMS cannot be adopted as one on the strength of two plausible bytes.
     */
    private const val MAX_KPA = 1000f

    /**
     * Tire pressure in kPa, or null when this is not one of these sensors.
     *
     * [address] is checked against the tail of the payload: these units repeat
     * their own MAC at the end, which is how a receiver tells four identical
     * caps apart, and checking it is what stops another 0x00AC device being
     * read as a tyre.
     */
    fun pressureKpa(companyId: Int, data: ByteArray, address: String?): Float? {
        if (companyId != COMPANY_ID) return null
        if (data.size != LENGTH) return null
        if (address != null && !tailMatches(data, address)) return null
        val raw = ((data[PRESSURE_AT].toInt() and 0xFF) shl 8) or
            (data[PRESSURE_AT + 1].toInt() and 0xFF)
        val kpa = raw / PER_KPA
        // 0 is a sensor with nothing to say, not a flat tyre, and the same
        // rule the rest of the pressure code follows.
        return kpa.takeIf { it > 0f && it <= MAX_KPA }
    }

    /** The last three MAC bytes, reversed, sit at the end of the payload. */
    private fun tailMatches(data: ByteArray, address: String): Boolean {
        val bytes = address.split(":").mapNotNull { it.toIntOrNull(16) }
        if (bytes.size != 6) return false
        // Address 5B:61:1B:11:11:11 ends the payload as 1B 61 5B.
        return (data[12].toInt() and 0xFF) == bytes[2] &&
            (data[13].toInt() and 0xFF) == bytes[1] &&
            (data[14].toInt() and 0xFF) == bytes[0]
    }
}
